# Customer Reviews verification against V10

Customer baseline `9726878` was tested against Integrated Spring Boot revision `dfe22d3` and its genuine PostgreSQL Flyway V10 schema. The test ran in an ignored Customer scratch copy using the Integrated ordering test fixture to provision a temporary TLS certificate, scoped terminal and staff credentials, and an isolated database schema. The Customer `RestReviewClient` and `ReviewState` ran unchanged against that HTTPS server. The fixture removed the disposable schema on shutdown.

| Check | Observed result |
| --- | --- |
| Open session and READY order | Server eligibility remained false. Submission before handoff returned `REVIEW_NOT_ELIGIBLE`. |
| Explicit SERVED handoff | Authorized staff operation returned 200. Server eligibility became true. |
| Review submission | Customer received the accepted persisted review and its server-issued ID. |
| Replay and duplicate | Identical replay returned the same receipt. A new key for the same visit returned `REVIEW_ALREADY_EXISTS`. |
| Session isolation | Another terminal received scoped `NOT_FOUND` for the session review. |
| Reopening | A fresh Customer state loaded the same persisted receipt and prevented further submissions. |

The isolated genuine-backend test passed with one test executed, zero failures and zero skips. This was an HTTPS and PostgreSQL verification on one host. Two-PC deployment and provider payment verification remain separate.
