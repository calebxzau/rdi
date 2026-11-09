# Login screenshot reference

LoginReference.kt is copied from the existing Compose LoginScreen.kt on2026-11-07.
Only function names and initial remembered fixture state are changed. Auto-login
is disabled and account data is inert. ReferenceMain.kt supplies a flat background
and explicit density. The production Compose screen is unchanged.

Build with tools/u4b0-reference.init.gradle, then capture with u4b0-reference.ps1.
These fixtures validate appearance, not authentication.
