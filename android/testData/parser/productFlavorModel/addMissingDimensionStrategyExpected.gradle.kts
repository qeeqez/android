val refToVal by extra("boo")
android {
  defaultConfig {
    missingDimensionStrategy("dim", "val1", "val2")
    missingDimensionStrategy("otherDim", refToVal)
  }
}
