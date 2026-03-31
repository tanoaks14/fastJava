package com.fastjava.servlet;

import java.util.Enumeration;
import java.util.Map;

/**
 * Minimal filter configuration contract.
 */
public interface FilterConfig {
    String getFilterName();

    String getInitParameter(String name);

    Enumeration<String> getInitParameterNames();

    Map<String, String> getInitParameters();
}