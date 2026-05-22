package com.playground.massinggen.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.massinggen.application.dto.BriefMetadata;
import com.playground.massinggen.application.dto.ExtractedProgram;
import com.playground.massinggen.application.dto.GenerateMassingCommand;
import com.playground.massinggen.application.dto.GenerateMassingResult;
import com.playground.massinggen.application.port.ArchOutputRepository;
import com.playground.massinggen.application.port.BriefMetadataPort;
import com.playground.massinggen.application.port.BriefProgramExtractor;
import com.playground.massinggen.application.port.Rhino3dmPort;
import com.playground.massinggen.application.properties.MassingProperties;
import com.playground.massinggen.domain.algorithm.MassingAlgorithm;
import com.playground.massinggen.domain.exception.MassingErrorCode;
import com.playground.massinggen.domain.exception.MassingException;
import com.playground.massinggen.domain.model.Program;
import com.playground.massinggen.domain.model.Room;
import com.playground.massinggen.domain.model.RoomBox;
import com.playground.massinggen.domain.model.SiteFootprint;
import com.playground.massinggen.domain.summary.MassingSummary;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Main M8 orchestrator per ADR-18 §1 + PRD Story 2 — wires the 8-step
 * workflow:
 * <ol>
 *   <li>Fetch brief metadata + body via {@link BriefMetadataPort} (HTTP to
 *       docs-api per ADR-08 §A08.12 Exception 5).</li>
 *   <li>Reject {@code extraction_status != "extracted"} as
 *       {@code BRIEF_NOT_READY}.</li>
 *   <li>Reject private + non-owner combos as
 *       {@code BRIEF_NOT_ACCESSIBLE} (defensive — docs-api typically
 *       already 404s these, but we check at the use-case layer for
 *       belt-and-suspenders tenant isolation).</li>
 *   <li>Call {@link BriefProgramExtractor} (Spring AI ChatClient →
 *       spark-inference-gateway per ADR-18 §4).</li>
 *   <li>Reconcile site / floor-height: extractor &gt; command &gt; defaults
 *       per ADR-18 §8.</li>
 *   <li>Run {@link MassingAlgorithm} (rectangular first-fit).</li>
 *   <li>Serialize via {@link Rhino3dmPort} (sidecar HTTP).</li>
 *   <li>Persist {@link ArchOutputRepository} row and assemble the
 *       {@link GenerateMassingResult}.</li>
 * </ol>
 *
 * <p>Errors propagate as {@link MassingException} — the controller advice
 * in {@code massing-gen-api} maps them to the wire shape per ADR-18 §7
 * (including the {@code <CODE>: <message>} prefix grammar of §6).
 */
@Service
public class GenerateMassingUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateMassingUseCase.class);

    private final BriefMetadataPort briefMetadataPort;
    private final BriefProgramExtractor briefProgramExtractor;
    private final MassingAlgorithm massingAlgorithm;
    private final Rhino3dmPort rhino3dmPort;
    private final ArchOutputRepository archOutputRepository;
    private final MassingProperties properties;
    private final ObjectMapper objectMapper;

    public GenerateMassingUseCase(
            BriefMetadataPort briefMetadataPort,
            BriefProgramExtractor briefProgramExtractor,
            MassingAlgorithm massingAlgorithm,
            Rhino3dmPort rhino3dmPort,
            ArchOutputRepository archOutputRepository,
            MassingProperties properties,
            ObjectMapper objectMapper) {
        this.briefMetadataPort = briefMetadataPort;
        this.briefProgramExtractor = briefProgramExtractor;
        this.massingAlgorithm = massingAlgorithm;
        this.rhino3dmPort = rhino3dmPort;
        this.archOutputRepository = archOutputRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public GenerateMassingResult execute(GenerateMassingCommand command) {
        long t0 = System.currentTimeMillis();

        // 1 + 2 + 3 — metadata fetch + readiness + access.
        BriefMetadata meta = briefMetadataPort.fetch(command.briefDocId(), command.caller());
        if (!"extracted".equals(meta.extractionStatus())) {
            throw new MassingException(
                    MassingErrorCode.BRIEF_NOT_READY,
                    "doc=" + command.briefDocId() + " status=" + meta.extractionStatus());
        }
        if ("private".equals(meta.visibility())
                && !command.caller().userId().equals(meta.ownerUserId())) {
            throw new MassingException(
                    MassingErrorCode.BRIEF_NOT_ACCESSIBLE,
                    "doc=" + command.briefDocId());
        }
        if (meta.body() == null || meta.body().isBlank()) {
            // Defensive — docs-api should never hand us an empty body for an
            // 'extracted' row, but if it does, treat as extraction failure.
            throw new MassingException(
                    MassingErrorCode.BRIEF_NOT_READY,
                    "doc=" + command.briefDocId() + " body is empty");
        }

        // 4 — LLM extraction.
        ExtractedProgram extracted = briefProgramExtractor.extract(meta.body());

        // 5 — reconcile site / floor-height.
        double siteWidth = firstNonNull(
                command.siteWidth(),
                extracted.siteWidthM(),
                properties.defaultSiteWidthM());
        double siteDepth = firstNonNull(
                command.siteDepth(),
                extracted.siteDepthM(),
                properties.defaultSiteDepthM());
        double floorHeight = firstNonNull(
                command.floorHeight(),
                extracted.floorHeightM(),
                properties.defaultFloorHeightM());

        SiteFootprint site = new SiteFootprint(siteWidth, siteDepth);
        List<Room> rooms = new ArrayList<>(extracted.rooms().size());
        for (ExtractedProgram.ExtractedRoom r : extracted.rooms()) {
            rooms.add(new Room(r.name(), r.areaM2()));
        }
        Program program = new Program(rooms, site, floorHeight);
        double totalArea = program.totalRoomAreaM2();

        // 6 — algorithm.
        List<RoomBox> boxes = massingAlgorithm.compute(program, properties.maxFloors());
        int floorCount = boxes.stream().mapToInt(RoomBox::floor).max().orElse(1);
        String summary = MassingSummary.format(rooms.size(), floorCount, totalArea);

        // 7 — .3dm serialization (sidecar HTTP). The adapter handles
        // breaker / timeout mapping; MassingException is thrown on failure.
        byte[] file = rhino3dmPort.serialize(boxes);

        // 8 — persist + assemble result.
        // Augment the rawProgramJson with the *effective* site/floor (i.e.,
        // the ones the algorithm actually used after reconciling extraction /
        // request / defaults) so the frontend's Program Details accordion
        // shows the geometry the user got.
        JsonNode effectiveJson = withEffectiveSite(
                extracted.rawProgramJson(), siteWidth, siteDepth, floorHeight);

        String briefSlug = BriefSlug.of(meta.title());
        ArchOutputRepository.SavedOutput saved = archOutputRepository.save(
                new ArchOutputRepository.NewOutput(
                        command.briefDocId(),
                        command.caller().userId(),
                        file,
                        toJsonString(effectiveJson),
                        totalArea,
                        floorCount,
                        summary,
                        briefSlug));

        long elapsedMs = System.currentTimeMillis() - t0;
        log.info(
                "massing_gen_complete outputId={} briefDocId={} rooms={} floors={} areaM2={} elapsedMs={}",
                saved.id(),
                command.briefDocId(),
                rooms.size(),
                floorCount,
                totalArea,
                elapsedMs);

        return new GenerateMassingResult(
                saved.id(), effectiveJson, totalArea, floorCount, summary);
    }

    private static double firstNonNull(Double a, Double b, double fallback) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return fallback;
    }

    private JsonNode withEffectiveSite(
            JsonNode raw, double siteWidth, double siteDepth, double floorHeight) {
        if (raw == null) {
            return objectMapper.createObjectNode()
                    .put("siteWidthM", siteWidth)
                    .put("siteDepthM", siteDepth)
                    .put("floorHeightM", floorHeight);
        }
        // Defensive copy — the extractor's raw tree is consumed elsewhere.
        var copy = raw.deepCopy();
        if (copy.isObject()) {
            var obj = (com.fasterxml.jackson.databind.node.ObjectNode) copy;
            obj.put("siteWidthM", siteWidth);
            obj.put("siteDepthM", siteDepth);
            obj.put("floorHeightM", floorHeight);
        }
        return copy;
    }

    private String toJsonString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("program_json_serialize_failed reason={}", e.toString());
            return "{}";
        }
    }
}
