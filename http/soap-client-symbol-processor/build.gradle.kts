import java.net.URLClassLoader
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
    `java-test-fixtures`
}

val cxf = configurations.create("cxf")

dependencies {
    api(projects.core.symbolProcessorCommon)

    cxf(libs.cxf.tools.wsdlto.core)
    cxf(libs.cxf.tools.wsdlto.frontend.jaxws)
    cxf(libs.cxf.tools.wsdlto.databinding.jaxb)
    cxf(libs.jakarta.xml.bind.api)
    cxf(libs.jakarta.xml.ws.api)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
    testImplementation(libs.jakarta.xml.bind.api)
    testImplementation(libs.glassfish.jaxb.jakarta)
    testImplementation(libs.jakarta.xml.ws.api)
    testImplementation(libs.cxf.rt.bindings.soap)
    testImplementation(libs.cxf.rt.transports.http.jetty)
    testImplementation(libs.cxf.rt.frontend.jaxws)
    testImplementation(projects.http.soapClient)
    testImplementation(projects.http.httpClientJdk)
}

fun wsdl2Java(path: String) {
    val wsdlName = path.substring(path.lastIndexOf('/') + 1).replace(".wsdl", "")
    val jakartaOutput = layout.buildDirectory.dir("generated/wsdl-jakarta-$wsdlName")

    val jakartaTask = tasks.register<CxfGenTask>("wsdl-jakarta-$wsdlName") {
        inputFile.set(project.layout.projectDirectory.file(path))
        outputDir.set(jakartaOutput)
        cxfClasspath.from(cxf)
    }

    tasks.withType<KotlinJvmCompile>().matching { it.name == "compileTestKotlin" }.configureEach {
        dependsOn(jakartaTask)
    }
}

wsdl2Java("src/test/wsdl/simple-service.wsdl")
wsdl2Java("src/test/wsdl/service-with-multipart-response.wsdl")
wsdl2Java("src/test/wsdl/service-with-rpc.wsdl")

abstract class CxfGenTask : DefaultTask() {

    @get:org.gradle.work.Incremental
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:CompileClasspath
    @get:InputFiles
    abstract val cxfClasspath: ConfigurableFileCollection

    @TaskAction
    fun execute() {
        val urls = cxfClasspath.files.map { it.toURI().toURL() }.toTypedArray()
        val cxfClassLoader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())

        val oldCl = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = cxfClassLoader
            val wsdlToJavaClass = cxfClassLoader.loadClass("org.apache.cxf.tools.wsdlto.WSDLToJava")
            val mainMethod = wsdlToJavaClass.getMethod("main", Array<String>::class.java)

            val args = arrayOf(
                "-d", outputDir.get().asFile.absolutePath,
                "-autoNameResolution",
                "-verbose",
                inputFile.get().asFile.absolutePath
            )

            mainMethod.invoke(null, args)
        } finally {
            Thread.currentThread().contextClassLoader = oldCl
        }
    }
}
