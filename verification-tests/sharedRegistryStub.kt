package app.gyrolet.mpvrx.domain.seekpreview
internal object SharedPlaybackTransportRegistry {
  fun cacheOnlyPreviewUrl(generation: Long, source: String): String? = null
  fun cachedByteRevision(generation: Long, source: String): Long = 0L
  fun previewUrl(generation: Long, source: String): String? = null
  fun mediaSizeBytes(generation: Long, source: String): Long? = null
  fun beginBudgetedAhead(generation: Long, source: String, plan: AheadPacing.Plan, canFetch: () -> Boolean): String? = null
  fun finishBudgetedAhead(generation: Long) {}
  fun setLargeForegroundFastMode(generation: Long, source: String, enabled: Boolean) {}
  fun suspendOnlyPreviews(generation: Long) {}
  fun resumeOnlyPreviews(generation: Long) {}
}
