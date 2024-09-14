package com.example.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

public class OpenTelemetryAgent {

    // premain 方法会在 JVM 启动时调用
    public static void premain(String agentArgs, Instrumentation inst) {
        // 解析参数
        Pattern pattern = Pattern.compile("([a-zA-Z\\.]+)#([a-zA-Z]+)");
        Matcher matcher = pattern.matcher(agentArgs);

        // matcher map to list
        var matcherList = new ArrayList<String[]>();

        while (matcher.find()) {
            matcherList.add(new String[]{matcher.group(1), matcher.group(2)});
        }

        System.out.println("OpenTelemetry Agent loaded." );

        // 注册字节码转换器
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                String finalClassName = className.replace("/", ".");

                var matcherItem = matcherList.stream().filter(matcher -> matcher[0].equals(finalClassName)).findFirst();
                if (matcherItem.isPresent()) {
                    System.out.println("Transforming class: " + finalClassName);
                    try {
                        ClassPool classPool = ClassPool.getDefault();
                        CtClass ctClass = classPool.get(finalClassName);

                        CtMethod method = ctClass.getDeclaredMethod(matcherItem.get()[1]);

                        method.addLocalVariable("span",
                                classPool.get("io.opentelemetry.javaagent.shaded.io.opentelemetry.api.trace.Span"));
                        method.addLocalVariable("scope",
                                classPool.get("io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Scope"));

                        String code = " " +
                                "   span = io.opentelemetry.javaagent.shaded.io.opentelemetry.api.GlobalOpenTelemetry.getTracer(\"my-agent-tracer\").spanBuilder(\"span_in_agent\").startSpan();"
                                +
                                "scope = span.makeCurrent();"
                                +
                                "  try {" +
                                "      System.out.println(\"OpenTelemetry agent span started\");span.addEvent(\"agent-event\");"
                                +
                                "  } catch (Throwable t) {" +
                                "      span.recordException(t);" +
                                "      throw t;" +
                                "  }" +
                                "";
                        method.insertBefore(
                                code);

                        method.insertAfter(
                                "scope.close(); span.end(); System.out.println(\"OpenTelemetry span ended\"); ");

                        return ctClass.toBytecode();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                return classfileBuffer;
            }
        }, false);
    }
}
