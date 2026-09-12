plugins {
	java
}

java {
	sourceCompatibility = JavaVersion.VERSION_11
	targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
	compileOnly(project(":runelite-api"))
	compileOnly(group = "org.projectlombok", name = "lombok", version = "1.18.30")
	annotationProcessor(group = "org.projectlombok", name = "lombok", version = "1.18.30")
	implementation(group = "com.google.inject", name = "guice", version = "5.0.1")
	implementation(group = "com.google.code.gson", name = "gson", version = "2.8.5")
	implementation(group = "org.slf4j", name = "slf4j-api", version = "1.7.32")
	// P6 hooks dumper: offline structural analysis of injected-client.oprs
	implementation(group = "org.ow2.asm", name = "asm", version = "9.7.1")
	implementation(group = "org.ow2.asm", name = "asm-tree", version = "9.7.1")

	testImplementation(project(":runelite-api"))
	testImplementation("org.mockito:mockito-inline:3.1.0")
	testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "5.9.2")
	testRuntimeOnly(group = "org.junit.platform", name = "junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
