package io.koraframework.gradle.soap

import org.gradle.workers.WorkAction

abstract class CxfRunnable : WorkAction<CxfWorkParameters> {
    override fun execute() {
        val wsdlToJavaClass = Class.forName("org.apache.cxf.tools.wsdlto.WSDLToJava")
        val mainMethod = wsdlToJavaClass.getMethod("main", Array<String>::class.java)
        mainMethod.invoke(null, parameters.args.get().toTypedArray())
    }
}
