package com.playground.massinggen.domain.algorithm;

import com.playground.massinggen.domain.model.Program;
import com.playground.massinggen.domain.model.RoomBox;
import java.util.List;

/**
 * Strategy interface for the rectangular massing solver per ADR-18 §8 +
 * spec §6 working algorithm.
 *
 * <p>{@link #compute(Program, int)} takes the extracted program (rooms +
 * site footprint + floor height) and the operator-configured
 * {@code maxFloors} cap; it returns a deterministic list of {@link RoomBox}
 * instances that fit inside the site footprint and respect the floor cap.
 *
 * <p>Over-area policy (ADR-18 §8): if
 * {@code ceil(totalRoomArea / siteArea) &gt; maxFloors}, the implementation
 * throws {@link com.playground.massinggen.domain.exception.MassingException}
 * with {@link com.playground.massinggen.domain.exception.MassingErrorCode#MASSING_ALGORITHM_FAILED}.
 * No auto-adjust, no auto-scale — the architect's mental model of "this
 * brief needs N floors" is preserved by failing loudly.
 *
 * <p>The default implementation is
 * {@link RectangularFirstFitMassingAlgorithm} (first-fit + area balance).
 * Future M8.1 strategies (skyline / shelf / 2D bin packing optimal) swap
 * in by implementing this interface; the use case is interface-bound so
 * the swap is a one-bean replacement.
 */
public interface MassingAlgorithm {

    /**
     * Compute a list of room boxes for the extracted program.
     *
     * @param program   the extracted program (rooms + site footprint +
     *                  floor height); never null
     * @param maxFloors the operator cap; if the program needs more floors,
     *                  throws {@code MassingException(MASSING_ALGORITHM_FAILED)}
     * @return immutable list of {@link RoomBox} — one entry per room in
     *         the program, ordered by floor then by area-descending
     *         placement order
     */
    List<RoomBox> compute(Program program, int maxFloors);
}
