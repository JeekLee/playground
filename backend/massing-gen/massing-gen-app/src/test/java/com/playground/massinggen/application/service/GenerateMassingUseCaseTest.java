package com.playground.massinggen.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.massinggen.application.dto.BriefMetadata;
import com.playground.massinggen.application.dto.ExtractedProgram;
import com.playground.massinggen.application.dto.GenerateMassingCommand;
import com.playground.massinggen.application.dto.GenerateMassingResult;
import com.playground.massinggen.application.dto.UserContext;
import com.playground.massinggen.application.port.ArchOutputRepository;
import com.playground.massinggen.application.port.BriefMetadataPort;
import com.playground.massinggen.application.port.BriefProgramExtractor;
import com.playground.massinggen.application.port.Rhino3dmPort;
import com.playground.massinggen.application.properties.MassingProperties;
import com.playground.massinggen.domain.algorithm.RectangularFirstFitMassingAlgorithm;
import com.playground.massinggen.domain.exception.MassingErrorCode;
import com.playground.massinggen.domain.exception.MassingException;
import com.playground.massinggen.domain.model.RoomBox;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Use-case slice tests for {@link GenerateMassingUseCase} per ADR-18 §22.
 */
class GenerateMassingUseCaseTest {

    private BriefMetadataPort briefMetadataPort;
    private BriefProgramExtractor extractor;
    private Rhino3dmPort rhino3dmPort;
    private ArchOutputRepository repository;
    private GenerateMassingUseCase useCase;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID BRIEF_ID = UUID.randomUUID();
    private static final UserContext CALLER = new UserContext(USER_ID, "google-sub-123");

    @BeforeEach
    void setUp() {
        briefMetadataPort = mock(BriefMetadataPort.class);
        extractor = mock(BriefProgramExtractor.class);
        rhino3dmPort = mock(Rhino3dmPort.class);
        repository = mock(ArchOutputRepository.class);
        MassingProperties props = new MassingProperties(
                10, 20.0, 10.0, 3.5,
                new MassingProperties.Rhino3dmBridge("http://test", 30000),
                new MassingProperties.DocsApi("http://docs", 5000));
        useCase = new GenerateMassingUseCase(
                briefMetadataPort,
                extractor,
                new RectangularFirstFitMassingAlgorithm(),
                rhino3dmPort,
                repository,
                props,
                new ObjectMapper());
    }

    @Test
    void happyPath_returnsResultWithSummaryAndFileUrl() {
        ObjectMapper om = new ObjectMapper();
        when(briefMetadataPort.fetch(eq(BRIEF_ID), eq(CALLER)))
                .thenReturn(new BriefMetadata(
                        BRIEF_ID, "test-brief", "Markdown body...",
                        "extracted", "private", USER_ID));
        var extractedJson = om.createObjectNode();
        extractedJson.putArray("rooms"); // populated below via extractor mock
        when(extractor.extract(any())).thenReturn(new ExtractedProgram(
                List.of(
                        new ExtractedProgram.ExtractedRoom("로비", 48.0),
                        new ExtractedProgram.ExtractedRoom("강의실", 80.0),
                        new ExtractedProgram.ExtractedRoom("카페", 30.0)),
                null, null, null,
                extractedJson));
        byte[] fakeBytes = "3DMfakeBytes".getBytes();
        when(rhino3dmPort.serialize(any())).thenReturn(fakeBytes);
        UUID outputId = UUID.randomUUID();
        when(repository.save(any())).thenReturn(
                new ArchOutputRepository.SavedOutput(outputId, Instant.now()));

        GenerateMassingCommand cmd = new GenerateMassingCommand(
                BRIEF_ID, 20.0, 10.0, 3.5, CALLER);
        GenerateMassingResult result = useCase.execute(cmd);

        assertThat(result.outputId()).isEqualTo(outputId);
        assertThat(result.totalAreaM2()).isEqualTo(158.0);
        assertThat(result.summary()).contains("3실");
        assertThat(result.summary()).contains("총 158 m²");
        assertThat(result.fileUrl()).isEqualTo("/api/arch/outputs/" + outputId);
    }

    @Test
    void briefNotExtracted_throwsBriefNotReady() {
        when(briefMetadataPort.fetch(eq(BRIEF_ID), eq(CALLER)))
                .thenReturn(new BriefMetadata(
                        BRIEF_ID, "test", "", "processing", "private", USER_ID));

        GenerateMassingCommand cmd = new GenerateMassingCommand(
                BRIEF_ID, null, null, null, CALLER);
        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(MassingException.class)
                .matches(ex -> ((MassingException) ex).massingErrorCode()
                        == MassingErrorCode.BRIEF_NOT_READY);
    }

    @Test
    void privateBrief_callerNotOwner_throwsBriefNotAccessible() {
        when(briefMetadataPort.fetch(eq(BRIEF_ID), eq(CALLER)))
                .thenReturn(new BriefMetadata(
                        BRIEF_ID, "test", "Markdown",
                        "extracted", "private", OTHER_USER_ID));

        GenerateMassingCommand cmd = new GenerateMassingCommand(
                BRIEF_ID, null, null, null, CALLER);
        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(MassingException.class)
                .matches(ex -> ((MassingException) ex).massingErrorCode()
                        == MassingErrorCode.BRIEF_NOT_ACCESSIBLE);
    }

    @Test
    void publicBrief_callerNotOwner_isAllowed() {
        ObjectMapper om = new ObjectMapper();
        when(briefMetadataPort.fetch(eq(BRIEF_ID), eq(CALLER)))
                .thenReturn(new BriefMetadata(
                        BRIEF_ID, "test-public", "Markdown body...",
                        "extracted", "public", OTHER_USER_ID));
        when(extractor.extract(any())).thenReturn(new ExtractedProgram(
                List.of(new ExtractedProgram.ExtractedRoom("로비", 30.0)),
                null, null, null, om.createObjectNode()));
        when(rhino3dmPort.serialize(any())).thenReturn("bytes".getBytes());
        UUID outputId = UUID.randomUUID();
        when(repository.save(any())).thenReturn(
                new ArchOutputRepository.SavedOutput(outputId, Instant.now()));

        GenerateMassingCommand cmd = new GenerateMassingCommand(
                BRIEF_ID, 20.0, 10.0, 3.5, CALLER);
        GenerateMassingResult result = useCase.execute(cmd);
        assertThat(result.outputId()).isEqualTo(outputId);
    }

    @Test
    void overArea_throwsMassingAlgorithmFailed() {
        ObjectMapper om = new ObjectMapper();
        when(briefMetadataPort.fetch(eq(BRIEF_ID), eq(CALLER)))
                .thenReturn(new BriefMetadata(
                        BRIEF_ID, "huge", "Markdown",
                        "extracted", "private", USER_ID));
        // 30 rooms of 100 m² = 3000 m². Site 200 m² → 15 floors > maxFloors=10.
        List<ExtractedProgram.ExtractedRoom> rooms = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rooms.add(new ExtractedProgram.ExtractedRoom("실 " + i, 100.0));
        }
        when(extractor.extract(any())).thenReturn(new ExtractedProgram(
                rooms, null, null, null, om.createObjectNode()));

        GenerateMassingCommand cmd = new GenerateMassingCommand(
                BRIEF_ID, 20.0, 10.0, 3.5, CALLER);
        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(MassingException.class)
                .matches(ex -> ((MassingException) ex).massingErrorCode()
                        == MassingErrorCode.MASSING_ALGORITHM_FAILED);
    }

    @Test
    void userIdPropagatedToRepository() {
        ObjectMapper om = new ObjectMapper();
        when(briefMetadataPort.fetch(eq(BRIEF_ID), eq(CALLER)))
                .thenReturn(new BriefMetadata(
                        BRIEF_ID, "test", "Markdown",
                        "extracted", "private", USER_ID));
        when(extractor.extract(any())).thenReturn(new ExtractedProgram(
                List.of(new ExtractedProgram.ExtractedRoom("로비", 30.0)),
                null, null, null, om.createObjectNode()));
        when(rhino3dmPort.serialize(any())).thenReturn("bytes".getBytes());

        org.mockito.ArgumentCaptor<ArchOutputRepository.NewOutput> captor =
                org.mockito.ArgumentCaptor.forClass(ArchOutputRepository.NewOutput.class);
        when(repository.save(captor.capture())).thenReturn(
                new ArchOutputRepository.SavedOutput(UUID.randomUUID(), Instant.now()));

        GenerateMassingCommand cmd = new GenerateMassingCommand(
                BRIEF_ID, 20.0, 10.0, 3.5, CALLER);
        useCase.execute(cmd);

        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().briefDocId()).isEqualTo(BRIEF_ID);
    }

    @Test
    void siteDefaultsFallThrough_extractorThenRequestThenConfig() {
        ObjectMapper om = new ObjectMapper();
        when(briefMetadataPort.fetch(eq(BRIEF_ID), eq(CALLER)))
                .thenReturn(new BriefMetadata(
                        BRIEF_ID, "test", "Markdown",
                        "extracted", "private", USER_ID));
        when(extractor.extract(any())).thenReturn(new ExtractedProgram(
                List.of(new ExtractedProgram.ExtractedRoom("a", 30.0)),
                25.0, 12.0, null, // extractor says siteWidth=25 siteDepth=12
                om.createObjectNode()));
        when(rhino3dmPort.serialize(any())).thenReturn("bytes".getBytes());

        org.mockito.ArgumentCaptor<List<RoomBox>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        when(rhino3dmPort.serialize(captor.capture())).thenReturn("bytes".getBytes());
        when(repository.save(any())).thenReturn(
                new ArchOutputRepository.SavedOutput(UUID.randomUUID(), Instant.now()));

        // No request override on site dims → extractor value wins.
        // No floor height anywhere → fall back to config default (3.5).
        GenerateMassingCommand cmd = new GenerateMassingCommand(
                BRIEF_ID, null, null, null, CALLER);
        useCase.execute(cmd);

        // Sanity — boxes were built with the extractor-supplied site
        // (the algorithm has to fit at least one 30 m² room inside 25 × 12).
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).heightM()).isEqualTo(3.5);
    }
}
