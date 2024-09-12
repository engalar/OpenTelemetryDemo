package com.example.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

public class OpenTelemetryAgent {

    // premain 方法会在 JVM 启动时调用
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("OpenTelemetry Agent loaded.");

        // 注册字节码转换器
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                String finalClassName = className.replace("/", ".");

                if (finalClassName.equals("com.mendix.modules.microflowengine.microflow.impl.MicroflowImpl")) {
                    System.out.println("Transforming class: " + finalClassName);
                    try {
                        ClassPool classPool = ClassPool.getDefault();
                        CtClass ctClass = classPool.get(finalClassName);

                        // 获取要修改的方法
                        CtMethod method = ctClass.getDeclaredMethod("executeAction");

                        method.addLocalVariable("span", classPool.get("io.opentelemetry.api.trace.Span"));
                        method.addLocalVariable("scope", classPool.get("io.opentelemetry.context.Scope"));
                        // 在方法开始前插入 OpenTelemetry span
                        String code = " " +
                                "   span = io.opentelemetry.api.GlobalOpenTelemetry.getTracer(\"my-agent-span\").spanBuilder(\"doSomething\").startSpan();"+
                                "scope = span.makeCurrent();"
                                +
                                "  try {" +
                                "      System.out.println(\"OpenTelemetry span started\");span.addEvent(\"agent-event\");" +
                                "  } catch (Throwable t) {" +
                                "      span.recordException(t);" +
                                "      throw t;" +
                                "  }" +
                                "";
                        method.insertBefore(
                                code);

                        // 在方法结束后插入 span 结束
                        method.insertAfter("scope.close(); span.end(); System.out.println(\"OpenTelemetry span ended\"); ");

                        // 返回修改后的字节码
                        return ctClass.toBytecode();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return classfileBuffer; // 返回原始字节码
            }
        }, false);
    }
}
