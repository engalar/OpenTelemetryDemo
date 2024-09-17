package com.wengao.mendix.agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

public class OpenTelemetryAgent {

    // premain 方法会在 JVM 启动时调用
    public static void premain(String agentArgs, Instrumentation inst) {
        // 解析参数
        Pattern pattern = Pattern.compile("([a-zA-Z\\.]+)#([a-zA-Z]+)");
        Matcher matcher = pattern.matcher(agentArgs);

        // matcher map to list
        var matcherList = new ArrayList<String[]>();

        while (matcher.find()) {
            matcherList.add(new String[] { matcher.group(1), matcher.group(2) });
        }

        System.out.println("OpenTelemetry Agent loaded.");

        // 注册字节码转换器
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                String finalClassName = className.replace("/", ".");

                var matcherItem = matcherList.stream().filter(matcher -> matcher[0].equals(finalClassName)).findFirst();
                if (matcherItem.isPresent()) {
                    System.out.println("Transforming class: " + finalClassName);
                    ClassPool classPool = ClassPool.getDefault();
                    CtClass ctClass = null;
                    try {
                        ctClass = classPool.get(finalClassName);
                    } catch (NotFoundException e) {
                        System.out.println("Class not found: " + finalClassName);
                        e.printStackTrace();
                    }

                    CtMethod method = null;
                    try {
                        method = ctClass.getDeclaredMethod(matcherItem.get()[1]);
                    } catch (NotFoundException e) {
                        System.out.println("Method not found: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    }

                    try {
                        method.addLocalVariable("span",
                                classPool.get("io.opentelemetry.javaagent.shaded.io.opentelemetry.api.trace.Span"));
                    } catch (CannotCompileException e) {
                        System.out.println("CannotCompileException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    } catch (NotFoundException e) {
                        System.out.println("NotFoundException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    }
                    try {
                        method.addLocalVariable("scope",
                                classPool.get("io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Scope"));
                    } catch (CannotCompileException e) {
                        System.out.println("CannotCompileException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    } catch (NotFoundException e) {
                        System.out.println("NotFoundException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    }

                    String code = " " +
                            "   span = io.opentelemetry.javaagent.shaded.io.opentelemetry.api.GlobalOpenTelemetry.getTracer(\"my-agent-tracer\").spanBuilder(\"span_in_agent\").setParent(io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Context.current()).startSpan();"
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
                    try {
                        method.insertBefore(
                                code);
                    } catch (CannotCompileException e) {
                        System.out.println("CannotCompileException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    }

                    try {
                        method.insertAfter(
                                "scope.close(); span.end(); System.out.println(\"OpenTelemetry span ended\");");
                    } catch (CannotCompileException e) {
                        System.out.println("CannotCompileException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    }

                    try {
                        return ctClass.toBytecode();
                    } catch (IOException | CannotCompileException e) {
                        System.out.println("CannotCompileException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    }
                }

                return classfileBuffer;
            }
        }, false);
    }
}
