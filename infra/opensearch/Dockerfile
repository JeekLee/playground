# OpenSearch base image + analysis-nori plugin for Korean tokenization.
# The official opensearchproject/opensearch:2.18.0 image does NOT include
# analysis-nori in its baked plugin set (verified at integration time —
# `opensearch-plugin list` returns 23 plugins, none named analysis-nori).
#
# docs-api's projector creates the docs-v1 index with a Nori-based analyzer
# (ADR-12 §6, spec §4.2). Without this plugin the index creation fails
# 400 illegal_argument_exception "Unknown tokenizer type [nori_tokenizer]"
# and the search endpoint returns 503 (spec §10 search failure isolation).
FROM opensearchproject/opensearch:2.18.0

RUN /usr/share/opensearch/bin/opensearch-plugin install --batch analysis-nori
