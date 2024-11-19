package com.billforge.shared.annotation;

import com.billforge.shared.enums.billing.UsageMetric;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureGate {
    UsageMetric metric();
}
