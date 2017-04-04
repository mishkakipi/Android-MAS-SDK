/*
 *  Copyright (c) 2016 CA. All rights reserved.
 *
 *  This software may be modified and distributed under the terms
 *  of the MIT license.  See the LICENSE file for details.
 *
 */
package com.ca.mas.foundation;

import android.support.test.InstrumentationRegistry;
import android.util.Base64;
import android.util.Pair;

import com.ca.mas.DataSource;
import com.ca.mas.GatewayDefaultDispatcher;
import com.ca.mas.MASCallbackFuture;
import com.ca.mas.MASLoginTestBase;
import com.ca.mas.core.http.ContentType;
import com.ca.mas.core.util.KeyUtils;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.util.DateUtils;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

import junit.framework.Assert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.net.URI;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

public class MASJwtSigningTest extends MASLoginTestBase {

    @Test
    public void testJSONDefaultTimeoutPost() throws Exception {
        JSONObject requestData = new JSONObject();
        requestData.put("jsonName", "jsonValue");
        requestData.put("jsonName2", 1234);

        MASRequest request = new MASRequest.MASRequestBuilder(new URI(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS))
                .post(MASRequestBody.jsonBody(requestData))
                .sign()
                .build();
        MASCallbackFuture<MASResponse<JSONObject>> callback = new MASCallbackFuture<>();
        MAS.invoke(request, callback);

        callback.get();

        RecordedRequest rr = getRecordRequest(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS);
        String magIdentifier = rr.getHeader("mag-identifier");
        DataSource.Device device = DataSource.getInstance().getDevice(magIdentifier);
        String signedDoc = rr.getBody().readUtf8();

        JWSObject signedObject = JWSObject.parse(signedDoc);
        JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) device.getRegisteredPublicKey());
        Assert.assertTrue(signedObject.verify(verifier));
        net.minidev.json.JSONObject payload = signedObject.getPayload().toJSONObject();
        Assert.assertEquals(requestData.get("jsonName"), (new JSONObject(payload.get("content").toString())).get("jsonName"));
        Assert.assertEquals(payload.get("aud"), "localhost");
        // Expiry time should be equal to 5 minutes plus the specified timeout
        Assert.assertEquals((long) payload.get("iat") + 300, payload.get("exp"));
        Assert.assertEquals(payload.get("content-type"), ContentType.APPLICATION_JSON.getMimeType());
        Assert.assertNotNull(payload.get("iss"));
        Assert.assertEquals(MASUser.getCurrentUser().getUserName(), payload.get("sub"));
        //... assert other attribute
    }

    @Test
    public void testJSONCustomTimeoutSecondsPost() throws Exception {
        JSONObject requestData = new JSONObject();
        requestData.put("jsonName", "jsonValue");
        requestData.put("jsonName2", 1234);
        Calendar calendar = Calendar.getInstance(); // gets a calendar using the default time zone and locale.
        calendar.add(Calendar.MINUTE, 10);

        MASClaims claims = new MASClaims.MASClaimsBuilder().expirationTime(calendar.getTime()).build();
        MASRequest request = new MASRequest.MASRequestBuilder(new URI(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS))
                .post(MASRequestBody.jsonBody(requestData))
                .sign(claims)
                .build();
        MASCallbackFuture<MASResponse<JSONObject>> callback = new MASCallbackFuture<>();
        MAS.invoke(request, callback);

        callback.get();

        RecordedRequest rr = getRecordRequest(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS);
        String magIdentifier = rr.getHeader("mag-identifier");
        DataSource.Device device = DataSource.getInstance().getDevice(magIdentifier);
        String signedDoc = rr.getBody().readUtf8();

        JWSObject signedObject = JWSObject.parse(signedDoc);
        JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) device.getRegisteredPublicKey());
        Assert.assertTrue(signedObject.verify(verifier));
        net.minidev.json.JSONObject payload = signedObject.getPayload().toJSONObject();
        // Expiry time should be equal to the issued time plus the specified timeout
        Assert.assertEquals(DateUtils.toSecondsSinceEpoch(calendar.getTime()), payload.get("exp"));
        //... assert other attribute
    }

    @Test
    public void testStringPost() throws Exception {
        MASRequest request = new MASRequest.MASRequestBuilder(new URI(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS))
                .post(MASRequestBody.stringBody("test"))
                .sign()
                .build();
        MASCallbackFuture<MASResponse<JSONObject>> callback = new MASCallbackFuture<>();
        MAS.invoke(request, callback);

        callback.get();

        RecordedRequest rr = getRecordRequest(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS);
        String signedDoc = rr.getBody().readUtf8();
        JWSObject signedObject = JWSObject.parse(signedDoc);

        net.minidev.json.JSONObject payload = signedObject.getPayload().toJSONObject();
        Assert.assertEquals("test", payload.get(MASClaims.CONTENT));
    }

    @Test
    public void testFormPost() throws Exception {
        List<Pair<String, String>> form = new ArrayList<>();
        form.add(new Pair<String, String>("formfield1", "field1Value"));
        form.add(new Pair<String, String>("formfield1", "field2Value"));

        JSONObject expected = new JSONObject();
        for (Pair<String, String> pair : form) {
            if (pair.first != null) {
                try {
                    JSONArray jsonArray = (JSONArray) expected.opt(pair.first);
                    if (jsonArray == null) {
                        jsonArray = new JSONArray();
                        expected.put(pair.first, jsonArray);
                    }
                    if (pair.second != null) {
                        jsonArray.put(pair.second);
                    }
                } catch (JSONException e) {
                    //ignore
                }
            }
        }

        MASRequest request = new MASRequest.MASRequestBuilder(new URI(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS))
                .post(MASRequestBody.urlEncodedFormBody(form))
                .sign()
                .build();

        MASCallbackFuture<MASResponse<JSONObject>> callback = new MASCallbackFuture<>();
        MAS.invoke(request, callback);

        callback.get();

        RecordedRequest rr = getRecordRequest(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS);
        String signedDoc = rr.getBody().readUtf8();
        JWSObject signedObject = JWSObject.parse(signedDoc);

        net.minidev.json.JSONObject payload = signedObject.getPayload().toJSONObject();
        Assert.assertEquals(expected.get("formfield1"), (new JSONObject(payload.get(MASClaims.CONTENT).toString())).get("formfield1"));
    }

    @Test
    public void testByteArrayPost() throws Exception {
        MASRequest request = new MASRequest.MASRequestBuilder(new URI(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS))
                .post(MASRequestBody.byteArrayBody("test".getBytes()))
                .sign()
                .build();
        MASCallbackFuture<MASResponse<JSONObject>> callback = new MASCallbackFuture<>();
        MAS.invoke(request, callback);

        callback.get();

        RecordedRequest rr = getRecordRequest(GatewayDefaultDispatcher.PROTECTED_RESOURCE_PRODUCTS);
        String signedDoc = rr.getBody().readUtf8();
        JWSObject signedObject = JWSObject.parse(signedDoc);

        net.minidev.json.JSONObject payload = signedObject.getPayload().toJSONObject();
        Assert.assertEquals("test", new String(Base64.decode((String)payload.get(MASClaims.CONTENT), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_PADDING)));
    }

    @Test
    public void testSignWithPrivateKey() throws Exception, MASException {
        KeyUtils.deletePrivateKey("TEST");

        PrivateKey privateKey = KeyUtils.generateRsaPrivateKey(InstrumentationRegistry.getInstrumentation().getTargetContext(), 2048, "TEST", "dn=test", false, false, -1, false);
        PublicKey publicKey = KeyUtils.getRsaPublicKey("TEST");

        JSONObject requestData = new JSONObject();
        requestData.put("jsonName", "jsonValue");
        requestData.put("jsonName2", 1234);

        MASClaims claims = new MASClaims.MASClaimsBuilder().claim(MASClaims.CONTENT, requestData).build();
        String signedJWT = MAS.sign(claims, privateKey);

        JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) publicKey);
        assertTrue(JWSObject.parse(signedJWT).verify(verifier));

        //Clean up for the test
        KeyUtils.deletePrivateKey("TEST");

        net.minidev.json.JSONObject payload = JWSObject.parse(signedJWT).getPayload().toJSONObject();
        Assert.assertEquals(requestData.get("jsonName"), (new JSONObject(payload.get("content").toString())).get("jsonName"));

    }
}
