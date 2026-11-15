package io.github.yongyong14.parking_server.global.security;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@io.swagger.v3.oas.annotations.Parameter(hidden = true)
public @interface CurrentUser {}
