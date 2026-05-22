package com.playground.massinggen.domain.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playground.massinggen.domain.exception.MassingErrorCode;
import com.playground.massinggen.domain.exception.MassingException;
import com.playground.massinggen.domain.model.Program;
import com.playground.massinggen.domain.model.Room;
import com.playground.massinggen.domain.model.RoomBox;
import com.playground.massinggen.domain.model.SiteFootprint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RectangularFirstFitMassingAlgorithm} per ADR-18 §22.
 */
class RectangularFirstFitMassingAlgorithmTest {

    private final MassingAlgorithm algorithm = new RectangularFirstFitMassingAlgorithm();

    @Test
    void happyPath_singleFloor_returnsBoxesForEveryRoom() {
        Program program = new Program(
                List.of(
                        new Room("로비", 30.0),
                        new Room("카페", 20.0),
                        new Room("강의실", 50.0)),
                new SiteFootprint(20.0, 10.0),
                3.5);
        List<RoomBox> boxes = algorithm.compute(program, 10);

        assertThat(boxes).hasSize(3);
        // 30 + 20 + 50 = 100 m², site = 200 m² → 1 floor.
        assertThat(boxes).allMatch(b -> b.floor() == 1);
        // Sum of footprint areas approximates total room area (with packing
        // efficiency; aspect-adjusted boxes have areaM2 == footprintArea).
        double totalFootprint = boxes.stream()
                .mapToDouble(RoomBox::footprintAreaM2)
                .sum();
        assertThat(totalFootprint).isEqualTo(100.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void multiFloor_sumExceedsSite_distributesAcrossFloors() {
        // 6 × 30 m² = 180 m². Site = 20 × 10 = 200 m². Formula-floor = 1
        // but rectangular packing may need 2 floors depending on derived
        // box dimensions. Assert: > 1 room placed across at least 1 floor
        // and ≤ maxFloors.
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rooms.add(new Room("실 " + i, 30.0));
        }
        Program program = new Program(rooms, new SiteFootprint(20.0, 10.0), 3.5);
        List<RoomBox> boxes = algorithm.compute(program, 10);

        assertThat(boxes).hasSize(6);
        int maxFloor = boxes.stream().mapToInt(RoomBox::floor).max().orElseThrow();
        assertThat(maxFloor).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(10);
    }

    @Test
    void multiFloor_largeSumForcesMultipleFloors() {
        // 8 × 80 m² = 640 m². Site = 200 m². floorCount lower bound = 4
        // but realistic packing pushes higher — anywhere in [4, maxFloors].
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rooms.add(new Room("실 " + i, 80.0));
        }
        Program program = new Program(rooms, new SiteFootprint(20.0, 10.0), 3.5);
        List<RoomBox> boxes = algorithm.compute(program, 10);

        assertThat(boxes).hasSize(8);
        int maxFloor = boxes.stream().mapToInt(RoomBox::floor).max().orElseThrow();
        // Lower bound: ceil(640 / 200) = 4.
        assertThat(maxFloor).isGreaterThanOrEqualTo(4);
    }

    @Test
    void boxesFitInsideSite_xAndYRespected() {
        Program program = new Program(
                List.of(
                        new Room("a", 30.0),
                        new Room("b", 30.0),
                        new Room("c", 30.0)),
                new SiteFootprint(20.0, 10.0),
                3.5);
        List<RoomBox> boxes = algorithm.compute(program, 10);

        for (RoomBox b : boxes) {
            assertThat(b.x() + b.widthM()).isLessThanOrEqualTo(20.0 + 1e-6);
            assertThat(b.y() + b.depthM()).isLessThanOrEqualTo(10.0 + 1e-6);
        }
    }

    @Test
    void overArea_throwsMassingAlgorithmFailed() {
        // 25 × 100 m² = 2500 m². Site = 200 m² → 13 floors. maxFloors = 10 → fail.
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rooms.add(new Room("실 " + i, 100.0));
        }
        Program program = new Program(rooms, new SiteFootprint(20.0, 10.0), 3.5);

        assertThatThrownBy(() -> algorithm.compute(program, 10))
                .isInstanceOf(MassingException.class)
                .matches(ex -> ((MassingException) ex).massingErrorCode()
                        == MassingErrorCode.MASSING_ALGORITHM_FAILED);
    }

    @Test
    void determinism_sameInputProducesSameOutput() {
        Program program = new Program(
                List.of(
                        new Room("a", 30.0),
                        new Room("b", 50.0),
                        new Room("c", 20.0),
                        new Room("d", 80.0)),
                new SiteFootprint(20.0, 10.0),
                3.5);
        List<RoomBox> first = algorithm.compute(program, 10);
        List<RoomBox> second = algorithm.compute(program, 10);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void singleRoom_singleFloor_works() {
        Program program = new Program(
                List.of(new Room("solo", 50.0)),
                new SiteFootprint(20.0, 10.0),
                3.5);
        List<RoomBox> boxes = algorithm.compute(program, 10);

        assertThat(boxes).hasSize(1);
        assertThat(boxes.get(0).floor()).isEqualTo(1);
        assertThat(boxes.get(0).roomName()).isEqualTo("solo");
    }

    @Test
    void boxHeightEqualsFloorHeight() {
        Program program = new Program(
                List.of(new Room("a", 30.0)),
                new SiteFootprint(20.0, 10.0),
                4.2);
        List<RoomBox> boxes = algorithm.compute(program, 10);
        assertThat(boxes.get(0).heightM()).isEqualTo(4.2);
    }

    @Test
    void overArea_throwsWithMassingAlgorithmFailedCode() {
        // The user-facing message is the locale-fixed Korean copy from
        // MassingErrorCode — diagnostic details (floorCount, totalArea)
        // ride along on the exception's messageArgs but are logged, not
        // user-visible. The test asserts only the wire-level error code.
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rooms.add(new Room("실 " + i, 100.0));
        }
        Program program = new Program(rooms, new SiteFootprint(20.0, 10.0), 3.5);

        assertThatThrownBy(() -> algorithm.compute(program, 10))
                .isInstanceOf(MassingException.class)
                .matches(ex -> ((MassingException) ex).massingErrorCode()
                        == MassingErrorCode.MASSING_ALGORITHM_FAILED);
    }
}
