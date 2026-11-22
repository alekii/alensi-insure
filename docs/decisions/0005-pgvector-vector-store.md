# ADR-0005: PostgreSQL with pgvector as the Vector Store

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

RAG needs a store for embeddings of policy wordings, product terms, claims procedures, and underwriting guidelines, with similarity search filtered by product, product version, and line of business. See [RAG Architecture](../ai/rag-architecture.md).

The expected corpus is small: tens of products, a few versions each, plus procedures — on the order of 10,000–100,000 chunks. Insure already runs PostgreSQL as its primary database.

Options considered: pgvector (a PostgreSQL extension), a dedicated vector database (Qdrant, Weaviate, Milvus, Pinecone), Redis vector search, OpenSearch / Elasticsearch k-NN.

## Decision

Use **PostgreSQL with the pgvector extension** in Insure's existing database, schema `intelligence`, table `knowledge_chunk`, accessed through Spring AI's `PgVectorStore`.

- Column `embedding vector(1536)` (dimension set by the configured embedding model).
- **HNSW** index with cosine distance (`vector_cosine_ops`, `m = 16`, `ef_construction = 64`).
- GIN index on `metadata` for filtering.
- Schema managed by Flyway; Spring AI `initialize-schema` disabled.

## Why

- **No new infrastructure.** Same database, backups, high availability, monitoring, credentials, and Terraform as the rest of Insure. AWS RDS and Aurora PostgreSQL support pgvector.
- **Transactional consistency.** Activating a new wording version (insert chunks, mark old document `SUPERSEDED`) is one transaction. A dedicated store would need its own consistency handling.
- **SQL filtering.** Retrieval filters on product version and status with ordinary predicates, and chunks can reference `knowledge_document` with a foreign key.
- **Sufficient performance.** HNSW gives low-millisecond approximate search at this corpus size; dedicated vector databases pay off at tens of millions of vectors or very high query rates.
- **Portable.** Spring AI's `VectorStore` abstraction means moving to another store later changes configuration and an adapter, not use-case code.

## Consequences

- Vector queries share database resources with transactional traffic. At current scale this is negligible; if retrieval load grows, a read replica can serve it.
- The embedding dimension is fixed in the schema. Changing embedding model requires re-embedding into a new table (procedure in [RAG Architecture](../ai/rag-architecture.md#re-embedding)).
- The pgvector extension must be enabled in every environment (`CREATE EXTENSION vector`); local Docker Compose uses the `pgvector/pgvector` PostgreSQL image.
- Hybrid search (vector + PostgreSQL full-text) is available later without new infrastructure if clause-number queries need it.

## Alternatives Considered

- **Dedicated vector database (Qdrant, Weaviate, Milvus).** Better at very large scale and offers richer vector features, but adds a stateful system to operate, secure, and back up, and loses transactional consistency with knowledge documents. Not justified at this scale.
- **Managed vector service (Pinecone).** Same consistency concerns, plus sending insurer documents to another external processor.
- **Redis vector search.** Redis here holds disposable cache data; the knowledge base must be durable.
- **OpenSearch k-NN.** Strong for hybrid search, but another cluster to operate for a small corpus.
