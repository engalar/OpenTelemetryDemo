package com.wengao.mendix.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.LoaderClassPath;

public class OpenTelemetryAgent {
    private static void customProcessing(Instrumentation inst, String agentArgs) {
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
            private ClassPool classPool = ClassPool.getDefault();
            private boolean initialized = false;
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (!initialized) {
                    classPool.insertClassPath(new LoaderClassPath(OpenTelemetryAgent.class.getClassLoader()));
                    initialized = true;
                }
                String finalClassName = className.replace("/", ".");

                var matcherItem = matcherList.stream().filter(matcher -> matcher[0].equals(finalClassName)).findFirst();
                if (matcherItem.isPresent()) {
                    System.out.println("Transforming class: " + finalClassName);
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
                        method.addLocalVariable("__span",
                                classPool.get("io.opentelemetry.javaagent.shaded.io.opentelemetry.api.trace.Span"));
                        method.addLocalVariable("__scope",
                                classPool.get("io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Scope"));
                        method.addLocalVariable("action", classPool.get("com.mendix.systemwideinterfaces.core.ICoreAction"));
                        method.addLocalVariable("actionName", classPool.get("java.lang.String"));
                    } catch (NotFoundException e) {
                        System.out.println("NotFoundException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    } catch (CannotCompileException e) {
                        System.out.println("CannotCompileException: " + matcherItem.get()[1]);
                        e.printStackTrace();
                    }

                    String code = " " +
                            "action = getContext().getActionStack().get(0); " +
                            "actionName = action != null ? action.getActionName() : \"root span\";"+
                            "   __span = io.opentelemetry.javaagent.shaded.io.opentelemetry.api.GlobalOpenTelemetry.getTracer(\"my-agent-tracer\").spanBuilder(actionName).setParent(io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Context.current()).startSpan();"
                            +
                            "__scope = __span.makeCurrent();"
                            +
                            "  try {" +
                            "      System.out.println(\"OpenTelemetry agent span started\");__span.addEvent(\"agent-event\");"
                            +
                            "  } catch (Throwable t) {" +
                            "      __span.recordException(t);" +
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
                                "__scope.close(); __span.end(); System.out.println(\"OpenTelemetry span ended\");");
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

    // premain 方法会在 JVM 启动时调用
    public static void premain(String agentArgs, Instrumentation inst) {

        try {
            // 进行额外处理，例如动态加载类，执行特定的代码等
            customProcessing(inst, agentArgs);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String getAgentJarPath() {
        try {
            URL url = OpenTelemetryAgent.class.getProtectionDomain().getCodeSource().getLocation();
            return url.toURI().getPath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static File getParentDirectory(File file) {
        File parentDir = file.getParentFile();
        return (parentDir != null && parentDir.isDirectory()) ? parentDir : null;
    }

    private static List<JarFile> getJarFileUrls(File directory, File excludeFile) {
        List<JarFile> jarFiles = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles != null) {
            for (File file : files) {
                if (!file.equals(excludeFile)) {
                    try {
                        jarFiles.add(new JarFile(file));
                    } catch (IOException e) {
                        System.err.println("无法转换JAR文件为URL: " + file.getName());
                        e.printStackTrace();
                    }
                }
            }
        } else {
            System.err.println("无法读取JAR文件所在目录");
        }
        return jarFiles;
    }

    private static URLClassLoader createUrlClassLoader(List<JarFile> urls) {
        // JarFile List to URL[]
        List<URL> jarUrls = new ArrayList<>();
        for (JarFile jarFile : urls) {
            try {
                jarUrls.add(new File(jarFile.getName()).toURI().toURL());
                System.out.println(jarFile.getName());
            } catch (IOException e) {
                System.err.println("无法转换JAR文件为URL: " + jarFile.getName());
                e.printStackTrace();
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader());
    }
}
