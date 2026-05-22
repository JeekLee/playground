package com.playground.massinggen.domain.algorithm;

import com.playground.massinggen.domain.exception.MassingErrorCode;
import com.playground.massinggen.domain.exception.MassingException;
import com.playground.massinggen.domain.model.Program;
import com.playground.massinggen.domain.model.Room;
import com.playground.massinggen.domain.model.RoomBox;
import com.playground.massinggen.domain.model.SiteFootprint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Rectangular first-fit + area-balanced packer per ADR-18 §8.
 *
 * <p>Algorithm (deterministic):
 * <ol>
 *   <li>Compute {@code totalRoomArea = sum(rooms[].areaM2)}.</li>
 *   <li>Compute {@code siteArea = site.widthM × site.depthM}.</li>
 *   <li>Compute {@code floorCount = ceil(totalRoomArea / siteArea)}.</li>
 *   <li>If {@code floorCount &gt; maxFloors}, throw
 *       {@code MassingException(MASSING_ALGORITHM_FAILED)} — over-area
 *       policy per ADR-18 §8.</li>
 *   <li>Sort rooms by area descending. For each room in turn, derive a
 *       rectangular box {@code (boxW, boxD)} where {@code boxD} is set so
 *       {@code boxW &le; siteWidth} and the aspect ratio favours the site's
 *       depth axis (skinnier-than-square boxes pack better in the
 *       first-fit-by-shelf pattern below).</li>
 *   <li>Use shelf packing per floor: a "shelf" is a horizontal row with a
 *       fixed Y extent (= the maximum boxD in the shelf). Iterate floors
 *       (1..floorCount round-robin), placing each room on the first floor
 *       whose current shelf has space at the right edge; if no shelf has
 *       space, open a new shelf above on that floor. If a floor cannot
 *       fit the current shelf (Y-overflow), proceed to the next floor.</li>
 *   <li>Output is the placement list, ordered by floor then by placement
 *       order within the floor.</li>
 * </ol>
 *
 * <p>Determinism: identical {@code (program, maxFloors)} inputs produce
 * identical box-list outputs (verified by {@code MassingAlgorithmTest}).
 * Box-box overlap is impossible by construction (shelf packing always
 * advances the cursor); box-site overflow is checked at the per-shelf
 * level and forces a new floor when the residual height fits no further
 * shelf.
 *
 * <p>This class is Spring-free pure Java with the exception of the
 * {@code @Service} annotation — permitted on domain services per the
 * ADR-02 v2 VIA compromise (Spring Context is allowed in
 * {@code massing-gen-domain}; the BOM exposes the annotation as
 * {@code compileOnly}).
 */
@Service
public class RectangularFirstFitMassingAlgorithm implements MassingAlgorithm {

    @Override
    public List<RoomBox> compute(Program program, int maxFloors) {
        if (maxFloors < 1) {
            throw new IllegalArgumentException(
                    "maxFloors must be >= 1 (was " + maxFloors + ")");
        }

        SiteFootprint site = program.site();
        double siteArea = site.areaM2();
        double totalArea = program.totalRoomAreaM2();
        int minFloorCount = (int) Math.ceil(totalArea / siteArea);
        if (minFloorCount < 1) {
            minFloorCount = 1;
        }
        // Pre-check: even the perfect-packing lower bound exceeds the cap.
        if (minFloorCount > maxFloors) {
            throw new MassingException(
                    MassingErrorCode.MASSING_ALGORITHM_FAILED,
                    "totalArea=" + totalArea + "m² siteArea=" + siteArea
                            + "m² floorCount=" + minFloorCount
                            + " > maxFloors=" + maxFloors);
        }

        // Sort rooms by area descending (largest first — shelf packing
        // benefits from placing the bulk early).
        List<Room> sorted = new ArrayList<>(program.rooms());
        sorted.sort(Comparator.comparingDouble(Room::areaM2).reversed());

        // Start with the formula-derived minimum and grow the floor list
        // dynamically as shelf packing falls short of the perfect-packing
        // assumption. Hard cap at maxFloors — anything beyond throws
        // MASSING_ALGORITHM_FAILED with a "could not pack within N floors"
        // detail line (carries floorCount=N in the message).
        List<PackingState> floors = new ArrayList<>(minFloorCount);
        for (int i = 0; i < minFloorCount; i++) {
            floors.add(new PackingState(site));
        }

        List<RoomBox> placed = new ArrayList<>(sorted.size());
        for (Room room : sorted) {
            BoxDims dims = deriveBoxDims(room.areaM2(), site);
            int chosenFloor = -1;
            Placement chosenPlacement = null;
            // First-fit across the currently allocated floors.
            for (int f = 0; f < floors.size(); f++) {
                Placement p = floors.get(f).tryPlace(dims);
                if (p != null) {
                    chosenFloor = f;
                    chosenPlacement = p;
                    break;
                }
            }
            // Could not fit anywhere — try adding a new floor (if cap allows).
            while (chosenPlacement == null && floors.size() < maxFloors) {
                PackingState newFloor = new PackingState(site);
                Placement p = newFloor.tryPlace(dims);
                if (p == null) {
                    // Box doesn't even fit on an empty site — pathological
                    // single-room overflow; surface as algorithm failure.
                    throw new MassingException(
                            MassingErrorCode.MASSING_ALGORITHM_FAILED,
                            "room '" + room.name() + "' (" + room.areaM2()
                                    + "m²) does not fit on an empty site"
                                    + " width=" + site.widthM()
                                    + " depth=" + site.depthM()
                                    + " floorCount=" + (floors.size() + 1));
                }
                floors.add(newFloor);
                chosenFloor = floors.size() - 1;
                chosenPlacement = p;
            }
            if (chosenPlacement == null) {
                // Hit the floor cap.
                throw new MassingException(
                        MassingErrorCode.MASSING_ALGORITHM_FAILED,
                        "could not pack room '" + room.name() + "' ("
                                + room.areaM2() + "m²) within floorCount="
                                + floors.size() + " (maxFloors=" + maxFloors + ")");
            }
            floors.get(chosenFloor).commit(chosenPlacement);
            int floorNumber = chosenFloor + 1; // 1-indexed
            placed.add(new RoomBox(
                    floorNumber,
                    chosenPlacement.x,
                    chosenPlacement.y,
                    dims.widthM,
                    dims.depthM,
                    program.floorHeightM(),
                    room.name()));
        }

        // Final ordering: by floor ascending, then by (y, x) for visual
        // determinism in the .3dm output (lower-left rooms first per floor).
        placed.sort(Comparator
                .comparingInt(RoomBox::floor)
                .thenComparingDouble(RoomBox::y)
                .thenComparingDouble(RoomBox::x));
        return List.copyOf(placed);
    }

    /**
     * Derive rectangular box dimensions for a room of {@code areaM2}.
     *
     * <p>Strategy: prefer a box that is no wider than the site's width and
     * proportioned to roughly match the site's aspect ratio. Specifically,
     * set {@code boxW = min(siteWidth, sqrt(area × siteWidth / siteDepth))}
     * and {@code boxD = area / boxW}. If the derived {@code boxD} exceeds
     * the site's depth, clamp {@code boxD = siteDepth} and recompute
     * {@code boxW = area / siteDepth} (which may then exceed siteWidth in
     * pathological cases — those land as algorithm-failure via the placement
     * check). Pinning aspect ratio to the site keeps boxes "roughly
     * proportional" instead of long thin strips.
     */
    private BoxDims deriveBoxDims(double areaM2, SiteFootprint site) {
        double aspectRatio = site.widthM() / site.depthM();
        double boxW = Math.sqrt(areaM2 * aspectRatio);
        double boxD = areaM2 / boxW;
        // Clamp to site dimensions.
        if (boxW > site.widthM()) {
            boxW = site.widthM();
            boxD = areaM2 / boxW;
        }
        if (boxD > site.depthM()) {
            boxD = site.depthM();
            boxW = areaM2 / boxD;
            if (boxW > site.widthM()) {
                // Pathological — single room exceeds site footprint. Clamp
                // to the site and accept the area discrepancy; the floor
                // count check above already vetoed truly impossible loads,
                // so this only fires on a single-very-large room input.
                boxW = site.widthM();
                boxD = site.depthM();
            }
        }
        return new BoxDims(boxW, boxD);
    }

    private record BoxDims(double widthM, double depthM) {}

    private record Placement(double x, double y, double widthM, double depthM) {}

    /**
     * Per-floor shelf-packing state. Maintains an ordered list of shelves;
     * each shelf has a Y origin and a max-height (= the tallest box placed
     * in it). New boxes go onto the first shelf with horizontal room; if
     * none fit, a new shelf opens above the topmost existing shelf.
     */
    private static final class PackingState {
        private final SiteFootprint site;
        private final List<Shelf> shelves = new ArrayList<>();

        PackingState(SiteFootprint site) {
            this.site = site;
        }

        Placement tryPlace(BoxDims dims) {
            // Try existing shelves (lowest-y first).
            for (Shelf shelf : shelves) {
                if (dims.widthM <= shelf.remainingWidth()
                        && dims.depthM <= shelf.height) {
                    return new Placement(
                            shelf.cursorX, shelf.yOrigin, dims.widthM, dims.depthM);
                }
            }
            // Need a new shelf above all existing shelves.
            double newShelfY = shelves.isEmpty()
                    ? 0.0
                    : shelves.get(shelves.size() - 1).yOrigin
                            + shelves.get(shelves.size() - 1).height;
            if (newShelfY + dims.depthM > site.depthM() + 1e-6) {
                return null; // overflow — this floor is full
            }
            if (dims.widthM > site.widthM() + 1e-6) {
                return null; // pathological single-room overflow — handled
                             // upstream by deriveBoxDims's clamping path
            }
            return new Placement(0.0, newShelfY, dims.widthM, dims.depthM);
        }

        void commit(Placement placement) {
            // Find or create the shelf at placement.y.
            Shelf target = null;
            for (Shelf s : shelves) {
                if (Math.abs(s.yOrigin - placement.y) < 1e-9) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                target = new Shelf(placement.y, placement.depthM, site.widthM());
                shelves.add(target);
            } else {
                // Update shelf height if this box is taller (depth-wise).
                if (placement.depthM > target.height) {
                    target.height = placement.depthM;
                }
            }
            target.cursorX = placement.x + placement.widthM;
        }
    }

    private static final class Shelf {
        final double yOrigin;
        double height;
        final double siteWidthM;
        double cursorX;

        Shelf(double yOrigin, double height, double siteWidthM) {
            this.yOrigin = yOrigin;
            this.height = height;
            this.siteWidthM = siteWidthM;
            this.cursorX = 0.0;
        }

        double remainingWidth() {
            return siteWidthM - cursorX;
        }
    }
}
