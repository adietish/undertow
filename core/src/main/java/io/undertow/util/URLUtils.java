/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.util;

import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Utilities for dealing with URLs
 *
 * @author Stuart Douglas
 */
public class URLUtils {

    private static final QueryStringParser QUERY_STRING_PARSER = new QueryStringParser() {
        @Override
        void handle(HttpServerExchange exchange, String key, String value) {
            exchange.addQueryParam(key, value);
        }
    };
    private static final QueryStringParser PATH_PARAM_PARSER = new QueryStringParser() {
        @Override
        void handle(HttpServerExchange exchange, String key, String value) {
            exchange.addPathParam(key, value);
        }
    };

    private URLUtils() {

    }

    public static void parseQueryString(final String string, final HttpServerExchange exchange, final String charset, final boolean doDecode) {
        QUERY_STRING_PARSER.parse(string, exchange, charset, doDecode);
    }

    public static void parsePathParms(final String string, final HttpServerExchange exchange, final String charset, final boolean doDecode) {
        PATH_PARAM_PARSER.parse(string, exchange, charset, doDecode);
    }

    /**
     * Decodes a URL. If the decoding fails for any reason then an IllegalArgumentException will be thrown.
     *
     * @param s           The string to decode
     * @param enc         The encoding
     * @param decodeSlash If slash characters should be decoded
     * @param buffer      The string builder to use as a buffer.
     * @return The decoded URL
     */
    public static String decode(String s, String enc, boolean decodeSlash, StringBuilder buffer) {
        buffer.setLength(0);
        boolean needToChange = false;
        int numChars = s.length();
        int i = 0;
        boolean mightRequireSlashEscape = false;

        char c;
        byte[] bytes = null;
        while (i < numChars) {
            c = s.charAt(i);
            switch (c) {
                case '+':
                    buffer.append(' ');
                    i++;
                    needToChange = true;
                    break;
                case '%':
                /*
                 * Starting with this instance of %, process all
                 * consecutive substrings of the form %xy. Each
                 * substring %xy will yield a byte. Convert all
                 * consecutive  bytes obtained this way to whatever
                 * character(s) they represent in the provided
                 * encoding.
                 */
                    try {
                        // (numChars-i)/3 is an upper bound for the number
                        // of remaining bytes
                        if (bytes == null) {
                            bytes = new byte[(numChars - i) / 3];
                        }
                        int pos = 0;

                        while (((i + 2) < numChars) && (c == '%')) {
                            char p1 = Character.toLowerCase(s.charAt(i + 1));
                            char p2 = Character.toLowerCase(s.charAt(i + 2));
                            int v = 0;
                            if (p1 >= '0' && p1 <= '9') {
                                v = (p1 - '0') << 4;
                            } else if (p1 >= 'a' && p1 <= 'f') {
                                v = (p1 - 'a' + 10) << 4;
                            } else {
                                throw UndertowMessages.MESSAGES.failedToDecodeURL(s, enc);
                            }
                            if (p2 >= '0' && p2 <= '9') {
                                v += (p2 - '0');
                            } else if (p2 >= 'a' && p2 <= 'f') {
                                v += (p2 - 'a' + 10);
                            } else {
                                throw UndertowMessages.MESSAGES.failedToDecodeURL(s, enc);
                            }
                            if (v < 0) {
                                throw UndertowMessages.MESSAGES.failedToDecodeURL(s, enc);
                            }
                            if(v == '/' || v== '\\') {
                                mightRequireSlashEscape = true;
                            }

                            bytes[pos++] = (byte) v;
                            i += 3;
                            if (i < numChars) {
                                c = s.charAt(i);
                            }
                        }

                        // A trailing, incomplete byte encoding such as
                        // "%x" will cause an exception to be thrown

                        if ((i < numChars) && (c == '%')) {
                            throw UndertowMessages.MESSAGES.failedToDecodeURL(s, enc);
                        }

                        String decoded = new String(bytes, 0, pos, enc);
                        if (!decodeSlash && mightRequireSlashEscape) {
                            //we need to re-encode slash characters
                            //this is yuck, but a corner case
                            int decPos = 0;
                            for (int j = 0; j < decoded.length(); ++j) {
                                char decChar = decoded.charAt(j);
                                if (decChar == '/') {
                                    buffer.append(decoded.substring(decPos, j));
                                    buffer.append("%2F");
                                    decPos = j + 1;
                                } else if (decChar == '\\') {
                                    buffer.append(decoded.substring(decPos, j));
                                    buffer.append("%5C");
                                    decPos = j + 1;
                                }
                            }
                            buffer.append(decoded.substring(decPos));
                        } else {
                            buffer.append(decoded);
                        }
                        mightRequireSlashEscape = false;
                    } catch (NumberFormatException e) {
                        throw UndertowMessages.MESSAGES.failedToDecodeURL(s, enc);
                    } catch (UnsupportedEncodingException e) {
                        throw UndertowMessages.MESSAGES.failedToDecodeURL(s, enc);
                    }
                    needToChange = true;
                    break;
                default:
                    buffer.append(c);
                    i++;
                    break;
            }
        }

        return (needToChange ? buffer.toString() : s);
    }

    private abstract static class QueryStringParser {

        void parse(final String string, final HttpServerExchange exchange, final String charset, final boolean doDecode) {
            try {
                int stringStart = 0;
                String attrName = null;
                for (int i = 0; i < string.length(); ++i) {
                    char c = string.charAt(i);
                    if (c == '=' && attrName == null) {
                        attrName = string.substring(stringStart, i);
                        stringStart = i + 1;
                    } else if (c == '&') {
                        if (attrName != null) {
                            handle(exchange, decode(charset, attrName, doDecode), decode(charset, string.substring(stringStart, i), doDecode));
                        } else {
                            handle(exchange, decode(charset, string.substring(stringStart, i), doDecode), "");
                        }
                        stringStart = i + 1;
                        attrName = null;
                    }
                }
                if (attrName != null) {
                    handle(exchange, decode(charset, attrName, doDecode), decode(charset, string.substring(stringStart, string.length()), doDecode));
                } else if (string.length() != stringStart) {
                    handle(exchange, decode(charset, string.substring(stringStart, string.length()), doDecode), "");
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        private String decode(String charset, String attrName, final boolean doDecode) throws UnsupportedEncodingException {
            if (doDecode) {
                return URLDecoder.decode(attrName, charset);
            }
            return attrName;
        }

        abstract void handle(final HttpServerExchange exchange, final String key, final String value);
    }
}
