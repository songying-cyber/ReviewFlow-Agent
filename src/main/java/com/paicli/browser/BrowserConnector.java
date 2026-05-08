package com.paicli.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
