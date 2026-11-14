# 1.1.0-alpha implementation record

Baseline: `2c9b516`, development branch `ccr-bc9e9633-4tnnd4`.
Main `e7de36d` has identical Java, tests and build scripts; only README and gitignore differ.
Historical DESIGN.md is preserved. The current target is docs/DESIGN-1.1.md.

Execution order: integrity and bounds; CDC and immutable account-scoped snapshot storage;
recovery-before-apply and journal replay; flavor detection and isolation; invite/login UX;
snapshot UI and server maintenance; migration and release scripts; adversarial and end-to-end tests.

No production data or running deployment is part of this working checkout.

Implemented the storage, safe-apply, multi-flavor, account UX, snapshot UI and release paths.
The original baseline passed its existing tests before implementation. The final alpha has
explicit automated and real-artifact rehearsal coverage; see RELEASE-STATUS.md for practical
limits and platform checks. The source repository's historical DESIGN.md was retained.
