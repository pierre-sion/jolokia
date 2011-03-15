package org.jolokia.request;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MalformedObjectNameException;

import org.jolokia.converter.StringToObjectConverter;

/*
 *  Copyright 2009-2010 Roland Huss
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


/**
 * Factory for creating {@link JmxRequest}s
 *
 * @author roland
 * @since Oct 29, 2009
 */
public final class JmxRequestFactory {

    // Pattern for detecting escaped slashes in URL encoded requests
    private static final Pattern SLASH_ESCAPE_PATTERN = Pattern.compile("^\\^?-*\\+?$");

    // private constructor for static class
    private JmxRequestFactory() { }

    /**
     *
     * Create a JMX request from a GET Request with a REST Url.
     * <p>
     * The REST-Url which gets recognized has the following format:
     * <p>
     * <pre>
     *    &lt;base_url&gt;/&lt;type&gt;/&lt;param1&gt;/&lt;param2&gt;/....
     * </pre>
     * <p>
     * where <code>base_url<code> is the URL specifying the overall servlet (including
     * the servlet context, something like "http://localhost:8080/j4p-agent"),
     * <code>type</code> the operational mode and <code>param1 .. paramN<code>
     * the provided parameters which are dependend on the <code>type<code>
     * <p>
     * The following types are recognized so far, along with there parameters:
     *
     * <ul>
     *   <li>Type: <b>read</b> ({@link RequestType#READ}<br/>
     *       Parameters: <code>param1<code> = MBean name, <code>param2</code> = Attribute name,
     *       <code>param3 ... paramN</code> = Inner Path.
     *       The inner path is optional and specifies a path into complex MBean attributes
     *       like collections or maps. If within collections/arrays/tabular data,
     *       <code>paramX</code> should specify
     *       a numeric index, in maps/composite data <code>paramX</code> is a used as a string
     *       key.</li>
     *   <li>Type: <b>write</b> ({@link RequestType#WRITE}<br/>
     *       Parameters: <code>param1</code> = MBean name, <code>param2</code> = Attribute name,
     *       <code>param3</code> = value, <code>param4 ... paramN</code> = Inner Path.
     *       The value must be URL encoded (with UTF-8 as charset), and must be convertible into
     *       a data structure</li>
     *   <li>Type: <b>exec</b> ({@link RequestType#EXEC}<br/>
     *       Parameters: <code>param1</code> = MBean name, <code>param2</code> = operation name,
     *       <code>param4 ... paramN</code> = arguments for the operation.
     *       The arguments must be URL encoded (with UTF-8 as charset), and must be convertable into
     *       a data structure</li>
     *    <li>Type: <b>version</b> ({@link RequestType#VERSION}<br/>
     *        Parameters: none
     *    <li>Type: <b>search</b> ({@link RequestType#SEARCH}<br/>
     *        Parameters: <code>param1</code> = MBean name pattern
     * </ul>
     * @param pPathInfo path info of HTTP request
     * @param pParameterMap HTTP Query parameters
     * @return a newly created {@link JmxRequest}
     */
    public static JmxRequest createGetRequest(String pPathInfo, Map<String,String[]> pParameterMap) {
        RequestType type = null;
        try {
            String pathInfo = extractPathInfo(pPathInfo, pParameterMap);

            // Get all path elements as a reverse stack
            Stack<String> elements = extractElementsFromPath(pathInfo);
            type = RequestType.getTypeByName(elements.pop());

            // Parse request
            return getProcessor(type).process(elements,extractParameters(pParameterMap));
        } catch (NoSuchElementException exp) {
            throw new IllegalArgumentException("Invalid path info " + pPathInfo,exp);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException("Invalid object name. " + e.getMessage(),e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Internal: Illegal encoding for URL conversion: " + e,e);
        } catch (EmptyStackException exp) {
            throw new IllegalArgumentException("Invalid arguments in pathinfo " + pPathInfo + (type != null ? " for command " + type : ""),exp);
        }
    }


    /**
     * Create a list of {@link JmxRequest}s from a JSON list representing jmx requests
     *
     *
     * @param pJsonRequests JSON representation of a list of {@link JmxRequest}
     * @param pParameterMap
     * @return list with one or more {@link JmxRequest}
     * @throws javax.management.MalformedObjectNameException if the MBean name within the request is invalid
     */
    public static List<JmxRequest> createPostRequests(List pJsonRequests, Map<String, String[]> pParameterMap)
            throws MalformedObjectNameException {
        List<JmxRequest> ret = new ArrayList<JmxRequest>();
        for (Object o : pJsonRequests) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("Not a request within the list of requests " + pJsonRequests +
                        ". Expected map, but found: " + o);
            }
            ret.add(createPostRequest((Map<String,?>) o,pParameterMap));
        }
        return ret;
    }

    /**
     * Create a single {@link JmxRequest}s from a JSON map representation of a request
     *
     *
     * @param pRequestMap JSON representation of a {@link JmxRequest}
     * @param pParameterMap
     * @return the created {@link JmxRequest}
     * @throws javax.management.MalformedObjectNameException if the MBean name within the request is invalid
     */
    public static JmxRequest createPostRequest(Map<String, ?> pRequestMap, Map<String, String[]> pParameterMap)
            throws MalformedObjectNameException {
        Map<String,String> params = mergeMaps((Map<String,String>) pRequestMap.get("config"),
                                              extractParameters(pParameterMap));
        RequestType type = RequestType.getTypeByName((String) pRequestMap.get("type"));
        return getProcessor(type).process(pRequestMap,params);
    }


    // Merge multiple maps to a single map, with the former taking precedence over later maps.
    // Has some optimizations for null map arguments
    private static Map<String,String> mergeMaps(Map<String,String> ... pMaps) {
        Map<String,String> ret = new HashMap<String,String>();
        if (pMaps.length == 2 && pMaps[0] == null) {
            return pMaps[1];
        } else if (pMaps.length == 2 && pMaps[1] == null) {
            return pMaps[0];
        } if (pMaps.length == 1) {
            return pMaps[0];
        } else if (pMaps.length > 0) {
            for (int i = pMaps.length - 1;i >= 0;i--) {
                if (pMaps[i] != null) {
                    ret.putAll(pMaps[i]);
                }
            }
        } else {
            return null;
        }
        return ret;
    }

    // ================================================================================================

    // Extract path info either from the 'real' URL path, or from an request parameter
    private static String extractPathInfo(String pPathInfo, Map<String, String[]> pParameterMap) {
        String pathInfo = pPathInfo;

        // If no pathinfo is given directly, we look for a query parameter named 'p'.
        // This variant is helpful, if there are problems with the server mangling
        // up the pathinfo (e.g. for security concerns, often '/','\',';' and other are not
        // allowed in encoded form within the pathinfo)
        if (pPathInfo == null || pPathInfo.length() == 0 || pathInfo.matches("^/+$")) {
            String[] vals = pParameterMap.get("p");
            if (vals != null && vals.length > 0) {
                pathInfo = vals[0];
            }
        }
        if (pathInfo != null && pathInfo.length() > 0) {
            return pathInfo;
        } else {
            return "";
            //throw new IllegalArgumentException("No pathinfo given and no query parameter 'p'");
        }
    }



    /*
    We need to use this special treating for slashes (i.e. to escape with '/-/') because URI encoding doesnt work
    well with HttpRequest.pathInfo() since in Tomcat/JBoss slash seems to be decoded to early so that it get messed up
    and answers with a "HTTP/1.x 400 Invalid URI: noSlash" without returning any further indications

    For the rest of unsafe chars, we use uri decoding (as anybody should do). It could be of course the case,
    that the pathinfo has been already uri decoded (dont know by heart)
     */
    private static Stack<String> extractElementsFromPath(String pPath) throws UnsupportedEncodingException {
        // Strip leadings slahes
        String cleanPath = pPath.replaceFirst("^/+","");
        String[] elements = cleanPath.split("/+");

        Stack<String> ret = new Stack<String>();
        Stack<String> elementStack = new Stack<String>();

        for (int i=elements.length-1;i>=0;i--) {
            elementStack.push(elements[i]);
        }

        extractElements(ret,elementStack,null);
        if (ret.size() == 0) {
            // If no request type (i.e. the agent is queried directly
            // we return version and server meta information instead
            ret.push(RequestType.VERSION.getName());
        }

        // Reverse stack
        Collections.reverse(ret);

        return ret;
    }

    private static void extractElements(Stack<String> pRet, Stack<String> pElementStack,StringBuffer pPreviousBuffer)
            throws UnsupportedEncodingException {
        if (pElementStack.isEmpty()) {
            if (pPreviousBuffer != null && pPreviousBuffer.length() > 0) {
                pRet.push(decode(pPreviousBuffer.toString()));
            }
            return;
        }
        String element = pElementStack.pop();
        Matcher matcher = SLASH_ESCAPE_PATTERN.matcher(element);
        if (matcher.matches()) {
            unescapeSlashes(element, pRet, pElementStack, pPreviousBuffer);
        } else {
            if (pPreviousBuffer != null) {
                pRet.push(decode(pPreviousBuffer.toString()));
            }
            pRet.push(decode(element));
            extractElements(pRet,pElementStack,null);
        }
    }

    private static void unescapeSlashes(String pCurrentElement, Stack<String> pRet,
                                        Stack<String> pElementStack, StringBuffer pPreviousBuffer) throws UnsupportedEncodingException {
        if (pRet.isEmpty()) {
            return;
        }
        StringBuffer val;

        // Special escape at the beginning indicates that this element belongs
        // to the next one
        if (pCurrentElement.substring(0,1).equals("^")) {
            val = new StringBuffer();
        } else if (pPreviousBuffer == null) {
            val = new StringBuffer(pRet.pop());
        } else {
            val = pPreviousBuffer;
        }
        // Append appropriate nr of slashes
        expandSlashes(val, pCurrentElement);

        // Special escape at the end indicates that this is the last element in the path
        if (!pCurrentElement.substring(pCurrentElement.length()-1, pCurrentElement.length()).equals("+")) {
            if (!pElementStack.isEmpty()) {
                val.append(decode(pElementStack.pop()));
            }
            extractElements(pRet,pElementStack,val);
        } else {
            pRet.push(decode(val.toString()));
            extractElements(pRet,pElementStack,null);
        }
        return;
    }

    private static void expandSlashes(StringBuffer pVal, String pElement) {
        for (int j=0;j< pElement.length();j++) {
            pVal.append("/");
        }
    }

    private static String decode(String s) {
        return s;
        //return URLDecoder.decode(s,"UTF-8");

    }

    private static List<String> prepareExtraArgs(Stack<String> pElements) {
        List<String> ret = new ArrayList<String>();
        while (!pElements.isEmpty()) {
            String element = pElements.pop();
            // Check for escapes
            while (element.endsWith("\\") && !pElements.isEmpty()) {
                element = element.substring(0,element.length() - 1) + "/" + pElements.pop();
            }
            ret.add(element);
        }
        return ret;
    }

    private static Map<String,String> extractParameters(Map<String,String[]> pParameterMap) {
        Map<String,String> ret = new HashMap<String, String>();
        if (pParameterMap != null) {
            for (Map.Entry<String,String[]> entry : pParameterMap.entrySet()) {
                String values[] = entry.getValue();
                if (values != null && values.length > 0) {
                    ret.put(entry.getKey(), values[0]);
                }
            }
        }
        return ret;
    }


    // ==================================================================================
    // Dedicated parser for the various operations. They are installed as static processors.

    // Get the request creator for a specific type
    private static Processor getProcessor(RequestType pType) {
        Processor processor = PROCESSOR_MAP.get(pType);
        if (processor == null) {
            throw new UnsupportedOperationException("Type " + pType + " is not supported (yet)");
        }
        return processor;
    }

    private interface Processor {
        // For GET requests
        JmxRequest process(Stack<String> e, Map<String, String> pParams)
                throws MalformedObjectNameException;

        // For POST requests
        JmxRequest process(Map<String, ?> requestMap, Map<String, String> pParams)
                throws MalformedObjectNameException;
    }

    private static final Map<RequestType,Processor> PROCESSOR_MAP;

    private static String popOrNull(Stack<String> e) {
        if (e != null && !e.isEmpty()) {
            return e.pop();
        } else {
            return null;
        }
    }

    static {
        PROCESSOR_MAP = new HashMap<RequestType, Processor>();
        PROCESSOR_MAP.put(RequestType.READ,new Processor() {

            public JmxRequest process(Stack<String> e, Map<String, String> pParams) throws MalformedObjectNameException {
                return new JmxReadRequest(
                        e.pop(),  // object name
                        popOrNull(e), // attributes (can be null)
                        prepareExtraArgs(e), // path
                        pParams);
            }

            public JmxRequest process(Map<String, ?> requestMap, Map<String, String> pParams)
                    throws MalformedObjectNameException {
                return new JmxReadRequest(requestMap,pParams);
            }
        });

        PROCESSOR_MAP.put(RequestType.WRITE,new Processor() {
            public JmxRequest process(Stack<String> e, Map<String, String> pParams) throws MalformedObjectNameException {
                return new JmxWriteRequest(
                        e.pop(), // object name
                        e.pop(), // attribute name
                        StringToObjectConverter.convertSpecialStringTags(e.pop()), // value
                        prepareExtraArgs(e), // path
                        pParams);
            }

            public JmxRequest process(Map<String, ?> requestMap, Map<String, String> pParams)
                    throws MalformedObjectNameException {
                return new JmxWriteRequest(requestMap,pParams);
            }
        });

        PROCESSOR_MAP.put(RequestType.EXEC,new Processor() {
            public JmxRequest process(Stack<String> e, Map<String, String> pParams) throws MalformedObjectNameException {
                return new JmxExecRequest(
                        e.pop(), // Object name
                        e.pop(), // Operation name
                        prepareArguments(e), // arguments
                        pParams);
            }

            private List<String> prepareArguments(Stack<String> e) {
                List<String> extraArgs = prepareExtraArgs(e);
                List<String> args = new ArrayList<String>();
                for (String arg : extraArgs) {
                    args.add(StringToObjectConverter.convertSpecialStringTags(arg));
                }
                return args;
            }


            public JmxRequest process(Map<String, ?> requestMap, Map<String, String> pParams)
                    throws MalformedObjectNameException {
                return new JmxExecRequest(requestMap,pParams);
            }
        });

        PROCESSOR_MAP.put(RequestType.LIST,new Processor() {
            public JmxRequest process(Stack<String> e, Map<String, String> pParams) throws MalformedObjectNameException {
                return new JmxListRequest(
                        prepareExtraArgs(e), // path
                        pParams);
            }

            public JmxRequest process(Map<String, ?> requestMap, Map<String, String> pParams)
                    throws MalformedObjectNameException {
                return new JmxListRequest(requestMap,pParams);
            }
        });

        PROCESSOR_MAP.put(RequestType.VERSION,new Processor() {

            public JmxRequest process(Stack<String> e, Map<String, String> pParams) throws MalformedObjectNameException {
                return new JmxVersionRequest(pParams);
            }

            public JmxRequest process(Map<String, ?> requestMap, Map<String, String> pParams)
                    throws MalformedObjectNameException {
                return new JmxVersionRequest(requestMap,pParams);
            }
        });

        PROCESSOR_MAP.put(RequestType.SEARCH,new Processor() {

            public JmxRequest process(Stack<String> e, Map<String, String> pParams) throws MalformedObjectNameException {
                return new JmxSearchRequest(e.pop(),pParams);
            }

            public JmxRequest process(Map<String, ?> requestMap, Map<String, String> pParams)
                    throws MalformedObjectNameException {
                return new JmxSearchRequest(requestMap,pParams);
            }
        });
    }


}
