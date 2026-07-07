# Consumer ProGuard rules for the :backup library. Merged into the consuming app's
# minification config. No public API needs keeping yet — the module has no reflective
# entry points beyond the androidx.startup Initializer (added in Plan 04), which
# androidx.startup already ships keep rules for.
