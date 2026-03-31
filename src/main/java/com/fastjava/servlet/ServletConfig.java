package com.fastjava.servlet;

import java.util.Enumeration;
import java.util.Map;

/**
 * Minimal servlet configuration contract.
 */
public interface ServletConfig {
    String getServletName();

    String getInitParameter(String name);

    Enumeration<String> getInitParameterNames();

    Map<String, String> getInitParameters();
}