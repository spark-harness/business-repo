package com.spark.common.spring.cleanarchitecture.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Service;

@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Service
public @interface UseCase {
    @AliasFor(annotation = Service.class, attribute = "value")
    String value() default "";
}
