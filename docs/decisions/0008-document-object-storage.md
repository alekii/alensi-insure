# ADR-0008: Documents in S3-Compatible Object Storage

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Customers, policies, and claims accumulate documents: identity documents, police abstracts, medical reports, invoices, photos, policy wordings. Files range from kilobytes to tens of megabytes, are written once, and are read occasionally. Some are highly sensitive.

## Decision

- File content is stored in **S3** (MinIO locally), never in PostgreSQL.
- PostgreSQL holds metadata: owner, type, content type, size, SHA-256, sensitivity, scan and extraction status.
- Keys: `documents/{ownerType}/{ownerId}/{documentId}` — no file names or personal data in keys.
- Server-side encryption with KMS keys; bucket is private with public access blocked.
- Upload: client registers the document, receives a presigned `PUT` URL (5 minutes, size and content-type constrained), then confirms. Content type is verified from magic bytes after upload.
- Every upload is **virus-scanned** before it can be downloaded or sent for extraction.
- Download: after a permission check (`insure.documents.read`, or `read-sensitive` for `SENSITIVE`/`MEDICAL`), the API redirects to a presigned `GET` URL valid for 5 minutes. Every download is audited.
- Object versioning and lifecycle rules enforce retention.

## Consequences

- The database stays small and backups fast; large files never pass through the API pods.
- Access control is still central: no one gets a URL without a permission check, and URLs expire.
- Document integrity can be verified against the stored hash, and identical hashes feed duplicate-claim detection.
- Two stores must be kept consistent: a metadata row without confirmed upload expires after 24 hours; orphan objects are removed by lifecycle rules.

## Alternatives Considered

- **`bytea` / large objects in PostgreSQL.** Rejected: bloats the database and backups, and streams files through the application.
- **Shared file system.** Rejected: no presigned access, harder encryption and lifecycle management on Kubernetes.
