/*
 *  *
 *  Copyright (C) 2009-2015 Dell, Inc.
 *  See annotations for authorship information
 *
 *  ====================================================================
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  ====================================================================
 *
 */

package org.dasein.cloud.aliyun.util.requester;

import org.apache.http.NameValuePair;
import org.apache.http.client.methods.RequestBuilder;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.aliyun.Aliyun;
import org.dasein.cloud.aliyun.util.requester.AliyunMethodInternal.Category;

import javax.swing.text.html.parser.Entity;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Created by Jeffrey Yan on 7/1/2015.
 *
 * @author Jeffrey Yan
 * @since 2015.05.1
 */
public class AliyunEcsRequestBuilderFactory extends AliyunRequestBuilderFactory {
    public AliyunEcsRequestBuilderFactory(Aliyun aliyun, Category category, String httpMethod, String action,
            Map<String, Object> parameters) {
        super(aliyun, category, httpMethod, action, parameters);
    }

    protected Map<String, String> getFrameworkParameters() {
        byte[][] accessKey = (byte[][]) aliyun.getContext().getConfigurationValue(Aliyun.DSN_ACCESS_KEY);

        Map<String, String> frameworkParameters = new HashMap<String, String>();
        frameworkParameters.put("Action", this.action);
        frameworkParameters.put("Version", "2014-05-26");
        frameworkParameters.put("AccessKeyId", new String(accessKey[0]));
        frameworkParameters.put("TimeStamp", aliyun.formatIso8601Date(new Date()));
        frameworkParameters.put("SignatureMethod", "HMAC-SHA1");
        frameworkParameters.put("SignatureVersion", "1.0");
        frameworkParameters.put("SignatureNonce", UUID.randomUUID().toString());
        frameworkParameters.put("Format", "JSON");
        return frameworkParameters;
    }

    @Override
    protected void applyFrameworkParameters(RequestBuilder requestBuilder) {
        for(Map.Entry<String, String> parameter : getFrameworkParameters().entrySet()) {
            requestBuilder.addParameter(parameter.getKey(), parameter.getValue());
        }
    }

    protected void sign(RequestBuilder requestBuilder) throws InternalException {
        Map<String, String> requestParameters = new HashMap<String, String>();
        for (NameValuePair nameValuePair : requestBuilder.getParameters()) {
            requestParameters.put(nameValuePair.getName(), nameValuePair.getValue());
        }
        requestParameters.putAll(this.parameters);

        String[] sortedKeys = requestParameters.keySet().toArray(new String[]{});
        Arrays.sort(sortedKeys);
        StringBuilder canonicalStringBuilder = new StringBuilder();
        for(String key : sortedKeys) {
            canonicalStringBuilder.append("&").append(urlEncode(key)).append("=")
                    .append(urlEncode(requestParameters.get(key)));
        }
        String canonicalString = canonicalStringBuilder.toString().substring(1);

        StringBuilder stringToSign = new StringBuilder();
        stringToSign.append(httpMethod).append("&").append(urlEncode("/")).append("&");
        stringToSign.append(urlEncode(canonicalString));

        byte[][] accessKey = ( byte[][] ) aliyun.getContext().getConfigurationValue(Aliyun.DSN_ACCESS_KEY);
        byte[] accessKeySecret = accessKey[1];
        byte[] secretKey = Arrays.copyOf(accessKeySecret, accessKeySecret.length + 1);
        secretKey[accessKeySecret.length] = '&';
        String signature = computeSignature(secretKey, stringToSign.toString());

        requestBuilder.addParameter("Signature", signature);
    }
}