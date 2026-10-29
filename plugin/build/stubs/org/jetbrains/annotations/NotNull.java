package org.jetbrains.annotations;

import java.lang.annotation.*;

/**
 * Stub compiler-only annotation, dipakai saat compile karena paper-api jar
 * menyebut NotNull tapi tidak menyertakan kelasnya di runtime.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.PACKAGE, ElementType.LOCAL_VARIABLE})
public @interface NotNull {
    String value() default "";
}