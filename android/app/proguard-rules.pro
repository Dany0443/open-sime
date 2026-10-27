# Open SIME Android R8 / ProGuard rules
# Keep domain models used in UI state and tests predictable if reflection is ever used.
-keepnames class md.sime.opensime.core.model.** { *; }

