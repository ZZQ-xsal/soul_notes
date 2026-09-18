package kurvcygnus.soulnotes.utils.lint;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法由于涉及反射或者其它的元编程能力而调用者敏感, 不可以被随意包装, 只应被直接调用.
 * @implNote JDK 内部存在同名注解 ({@code jdk.internal.reflect.CallerSensitive}), 但无法被外部使用, 故于此自行定义.
 * @author Kurv Cygnus
 * @since 1.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface CallerSensitive
{
    /**
     * 记录注解方法为什么是调用者敏感的说明.
     * @return 说明文本, 默认为空串
     */
    String value() default "";
}
