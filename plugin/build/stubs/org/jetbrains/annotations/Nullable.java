package org.jetbrains.annotations;

import java.lang.annotation.*;

/** Stub compiler-only annotation. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.PACKAGE, ElementType.LOCAL_VARIABLE})
public @interface Nullable {
    String value() default "";
}