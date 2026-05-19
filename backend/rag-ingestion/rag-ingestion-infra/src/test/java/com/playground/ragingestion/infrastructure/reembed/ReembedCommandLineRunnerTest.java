package com.playground.ragingestion.infrastructure.reembed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.application.service.ReembedService;
import com.playground.ragingestion.application.service.ReembedService.Outcome;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.infrastructure.config.ReembedProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

@ExtendWith(MockitoExtension.class)
class ReembedCommandLineRunnerTest {

    @Mock ReembedService service;
    @Mock ChunkRepository chunkRepository;
    @Mock ConfigurableApplicationContext ctx;

    @Test
    void scope_all_iterates_all_documents_and_returns_zero_on_clean_run() {
        ReembedProperties props = new ReembedProperties(); // default scope=all
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(chunkRepository.findAllDistinctDocumentIds()).thenReturn(List.of(a, b));
        when(service.reembedOne(any())).thenReturn(Outcome.SUCCESS);

        ReembedCommandLineRunner runner =
                new ReembedCommandLineRunner(props, service, chunkRepository, ctx);
        int exitCode = runner.runAndReportExitCode(new DefaultApplicationArguments());

        assertThat(exitCode).isZero();
        verify(service, times(2)).reembedOne(any(DocumentId.class));
    }

    @Test
    void any_failed_outcome_yields_exit_code_two() {
        ReembedProperties props = new ReembedProperties();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(chunkRepository.findAllDistinctDocumentIds()).thenReturn(List.of(a, b));
        when(service.reembedOne(any()))
                .thenReturn(Outcome.SUCCESS)
                .thenReturn(Outcome.FAILED);

        ReembedCommandLineRunner runner =
                new ReembedCommandLineRunner(props, service, chunkRepository, ctx);
        int exitCode = runner.runAndReportExitCode(new DefaultApplicationArguments());

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void scope_document_requires_document_id() {
        ReembedProperties props = new ReembedProperties();
        props.setScope("document");
        // documentId not set — should throw
        ReembedCommandLineRunner runner =
                new ReembedCommandLineRunner(props, service, chunkRepository, ctx);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> runner.runAndReportExitCode(new DefaultApplicationArguments()));
    }

    @Test
    void scope_user_uses_user_filter() {
        ReembedProperties props = new ReembedProperties();
        props.setScope("user");
        UUID uid = UUID.randomUUID();
        props.setUserId(uid);
        when(chunkRepository.findDistinctDocumentIdsByUser(uid))
                .thenReturn(List.of(UUID.randomUUID()));
        when(service.reembedOne(any())).thenReturn(Outcome.SUCCESS);

        ReembedCommandLineRunner runner =
                new ReembedCommandLineRunner(props, service, chunkRepository, ctx);
        int exitCode = runner.runAndReportExitCode(new DefaultApplicationArguments());

        assertThat(exitCode).isZero();
        verify(chunkRepository).findDistinctDocumentIdsByUser(uid);
    }

    @Test
    void skipped_outcomes_do_not_fail_the_run() {
        ReembedProperties props = new ReembedProperties();
        when(chunkRepository.findAllDistinctDocumentIds()).thenReturn(List.of(UUID.randomUUID()));
        when(service.reembedOne(any())).thenReturn(Outcome.SKIPPED);

        ReembedCommandLineRunner runner =
                new ReembedCommandLineRunner(props, service, chunkRepository, ctx);
        int exitCode = runner.runAndReportExitCode(new DefaultApplicationArguments());

        assertThat(exitCode).isZero();
    }
}
