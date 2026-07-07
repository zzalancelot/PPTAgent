// Root project is an aggregator only. All plugins are declared here with
// `apply false` and applied per-module. Concrete versions live in each module.
plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

group = "com.ppt.agent"
version = "0.0.1-SNAPSHOT"
description = "PPTAgent LLM Gateway"

extra["springBootVersion"] = "4.1.0"
extra["springAiVersion"] = "2.0.0"
