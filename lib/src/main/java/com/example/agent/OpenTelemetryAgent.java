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

                if (finalClassName.equals("org.example.App")) {
                    System.out.println("Transforming class: " + finalClassName);
                    try {
                        ClassPool classPool = ClassPool.getDefault();
                        CtClass ctClass = classPool.get(finalClassName);

                        // 获取要修改的方法
                        CtMethod method = ctClass.getDeclaredMethod("getGreeting");

                        // 在方法开始处插入代码
                        method.insertBefore("{ System.out.println(\"[java agent]: getGreeting method is called.\"); }");

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
