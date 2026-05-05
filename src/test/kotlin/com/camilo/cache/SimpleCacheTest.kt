package com.camilo.cache

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class SimpleCacheTest : BehaviorSpec({

    given("an empty SimpleCache") {
        val cache = SimpleCache<String, String>()

        `when`("size is checked") {
            then("it should be zero") {
                cache.size() shouldBe 0
            }
        }

        `when`("getting a missing key") {
            then("it should return null") {
                cache.get("missing").shouldBeNull()
            }
        }
    }

    given("a SimpleCache with a stored entry") {
        val cache = SimpleCache<String, String>()
        cache.put("key", "value")

        `when`("getting the stored key") {
            then("it should return the stored value") {
                cache.get("key") shouldBe "value"
            }
            then("the size should reflect the entry") {
                cache.size() shouldBe 1
            }
        }

        `when`("overwriting the same key") {
            cache.put("key", "newValue")

            then("it should return the latest value") {
                cache.get("key") shouldBe "newValue"
            }
            then("the size should remain one") {
                cache.size() shouldBe 1
            }
        }
    }

    given("a SimpleCache with multiple entries") {
        val cache = SimpleCache<String, Int>()
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)

        `when`("retrieving each key") {
            then("each value should be returned") {
                cache.get("a") shouldBe 1
                cache.get("b") shouldBe 2
                cache.get("c") shouldBe 3
            }
            then("the size should match the number of entries") {
                cache.size() shouldBe 3
            }
        }
    }

    given("a SimpleCache with an expired entry") {
        val cache = SimpleCache<String, String>()
        val expiredEntry = SimpleCache.CacheEntry("old", System.currentTimeMillis() - 120_000)

        @Suppress("UNCHECKED_CAST")
        val internalMap = SimpleCache::class.java.getDeclaredField("cache")
            .apply { isAccessible = true }
            .get(cache) as java.util.concurrent.ConcurrentHashMap<String, SimpleCache.CacheEntry<String>>
        internalMap["expired"] = expiredEntry

        `when`("getting the expired key") {
            then("it should return null") {
                cache.get("expired").shouldBeNull()
            }
        }
    }

    given("a SimpleCache used with non-string types") {
        val cache = SimpleCache<Int, List<String>>()
        cache.put(42, listOf("hello", "world"))

        `when`("getting the stored key") {
            then("it should return the original list") {
                cache.get(42) shouldBe listOf("hello", "world")
            }
        }
    }
})
