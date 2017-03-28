package org.twonote.rgwadmin4j.impl;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import okhttp3.*;
import org.twonote.rgwadmin4j.RgwAdminClient;
import org.twonote.rgwadmin4j.RgwAdminException;
import org.twonote.rgwadmin4j.model.CreateUserResponse;
import org.twonote.rgwadmin4j.model.GetBucketInfoResponse;
import org.twonote.rgwadmin4j.model.GetUserInfoResponse;
import org.twonote.rgwadmin4j.model.Quota;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

/** A.K.A. S3 admin as you (should) know... Created by petertc on 2/16/17. */
public class RgwAdminClientImpl implements RgwAdminClient {
  private static final Gson gson = new Gson();
  private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

  private static final RequestBody emptyBody = RequestBody.create(null, new byte[] {});

  private final String endpoint;
  private final OkHttpClient client;

  public RgwAdminClientImpl(String accessKey, String secretKey, String endpoint) {
    this.client =
        new OkHttpClient().newBuilder().addInterceptor(new S3Auth(accessKey, secretKey)).build();
    this.endpoint = endpoint;
  }

  /**
   * @param uid
   * @param userCaps In forms of [users|buckets|metadata|usage|zone]=[*|read|write|read, write]
   */
  @Override
  public void addUserCapability(String uid, String userCaps) {
    Request request =
        new Request.Builder()
            .put(emptyBody)
            .url(
                HttpUrl.parse(endpoint)
                    .newBuilder()
                    .addPathSegment("user")
                    .query("caps")
                    .addQueryParameter("uid", uid)
                    .addQueryParameter("user-caps", userCaps)
                    .build())
            .build();

    safeCall(request);
  }

  /**
   * @param uid
   * @param userCaps In forms of [users|buckets|metadata|usage|zone]=[*|read|write|read, write]
   */
  @Override
  public void deleteUserCapability(String uid, String userCaps) {
    Request request =
        new Request.Builder()
            .delete()
            .url(
                HttpUrl.parse(endpoint)
                    .newBuilder()
                    .addPathSegment("user")
                    .query("caps")
                    .addQueryParameter("uid", uid)
                    .addQueryParameter("user-caps", userCaps)
                    .build())
            .build();

    safeCall(request);
  }

  /**
   * The operation is success if the target is not exist in the system after the operation is
   * executed. The operation does not throw exception even if the target is not exist in the
   * beginning.
   *
   * @param bucketName
   */
  @Override
  public void removeBucket(String bucketName) {
    Request request =
        new Request.Builder()
            .delete()
            .url(
                HttpUrl.parse(endpoint)
                    .newBuilder()
                    .addPathSegment("bucket")
                    .addQueryParameter("bucket", bucketName)
                    .addQueryParameter("purge-objects", "true")
                    .build())
            .build();

    safeCall(request);
  }

  @Override
  public void linkBucket(String bucketName, String bucketId, String userId) {
    Request request =
        new Request.Builder()
            .put(emptyBody)
            .url(
                HttpUrl.parse(endpoint)
                    .newBuilder()
                    .addPathSegment("bucket")
                    .addQueryParameter("bucket", bucketName)
                    .addQueryParameter("bucket-id", bucketId)
                    .addQueryParameter("uid", userId)
                    .build())
            .build();

    safeCall(request);
  }

  @Override
  public Optional<GetBucketInfoResponse> getBucketInfo(String bucketName) {
    Request request =
        new Request.Builder()
            .get()
            .url(
                HttpUrl.parse(endpoint)
                    .newBuilder()
                    .addPathSegment("bucket")
                    .addQueryParameter("bucket", bucketName)
                    .build())
            .build();

    String resp = safeCall(request);
    return Optional.ofNullable(gson.fromJson(resp, GetBucketInfoResponse.class));
  }

  /**
   * Guarantee that the request is execute success and the connection is closed
   *
   * @param request
   * @return resp body in str; null if no body or status code == 404
   * @throws RgwAdminException if resp code != (200||404)
   */
  private String safeCall(Request request) {
    try (Response response = client.newCall(request).execute()) {
      if (response.code() == 404) {
        return null;
      }
      if (!response.isSuccessful()) {
        throw ErrorUtils.parseError(response);
      }
      ResponseBody body = response.body();
      if (body != null) {
        return response.body().string();
      } else {
        return null;
      }
    } catch (IOException e) {
      throw new RgwAdminException(500, "IOException", e);
    }
  }

  @Override
  public CreateUserResponse createUser(String userId) {
    return createUser(userId, false);
  }

  /**
   * Create user with limit
   *
   * @param userId
   * @param isLimit if specify, user can only have one bucket, and quota is 1TiB
   * @return
   */
  // TODO: quota
  @Override
  public CreateUserResponse createUser(String userId, boolean isLimit) {
    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(endpoint)
            .newBuilder()
            .addPathSegment("user")
            .addQueryParameter("uid", userId)
            .addQueryParameter("display-name", userId);

    if (isLimit) {
      urlBuilder.addQueryParameter("max-buckets", "1");
    }

    Request request = new Request.Builder().put(emptyBody).url(urlBuilder.build()).build();

    String resp = safeCall(request);
    return gson.fromJson(resp, CreateUserResponse.class);
  }

  @Override
  public Optional<GetUserInfoResponse> getUserInfo(String userId) {
    Request request =
        new Request.Builder()
            .get()
            .url(
                HttpUrl.parse(endpoint)
                    .newBuilder()
                    .addPathSegment("user")
                    .addQueryParameter("uid", userId)
                    .build())
            .build();

    String resp = safeCall(request);
    return Optional.ofNullable(gson.fromJson(resp, GetUserInfoResponse.class));
  }

  @Override
  public void modifyUser(String userId, Map<String, String> parameters) {
    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(endpoint)
            .newBuilder()
            .addPathSegment("user")
            .addQueryParameter("uid", userId);

    parameters
        .entrySet()
        .forEach(entry -> urlBuilder.addQueryParameter(entry.getKey(), entry.getValue()));

    Request request = new Request.Builder().post(emptyBody).url(urlBuilder.build()).build();

    safeCall(request);
  }

  @Override
  public void suspendUser(String userId) {
    modifyUser(userId, ImmutableMap.of("suspended", "true"));
  }

  /**
   * The operation is success if the user is not exist in the system after the operation is
   * executed. The operation does not throw exception even if the user is not exist in the
   * beginning.
   *
   * @param userId
   */
  @Override
  public void removeUser(String userId) {
    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(endpoint)
            .newBuilder()
            .addPathSegment("user")
            .addQueryParameter("uid", userId)
            .addQueryParameter("purge-data", "true");

    Request request = new Request.Builder().delete().url(urlBuilder.build()).build();

    safeCall(request);
  }

  @Override
  public Optional<Quota> getUserQuota(String userId) {
    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(endpoint)
            .newBuilder()
            .addPathSegment("user")
            .query("quota")
            .addQueryParameter("uid", userId)
            .addQueryParameter("quota-type", "user");

    Request request = new Request.Builder().get().url(urlBuilder.build()).build();

    String resp = safeCall(request);
    return Optional.ofNullable(gson.fromJson(resp, Quota.class));
  }

  /**
   * @param userId
   * @param maxObjects The max-objects setting allows you to specify the maximum number of objects.
   *     A negative value disables this setting.
   * @param maxSizeKB The max-size option allows you to specify a quota for the maximum number of
   *     bytes. A negative value disables this setting.
   */
  @Override
  public void setUserQuota(String userId, long maxObjects, long maxSizeKB) {
    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(endpoint)
            .newBuilder()
            .addPathSegment("user")
            .query("quota")
            .addQueryParameter("uid", userId)
            .addQueryParameter("quota-type", "user");

    String body =
        gson.toJson(
            ImmutableMap.of(
                "max_objects", String.valueOf(maxObjects),
                "max_size_kb", String.valueOf(maxSizeKB),
                "enabled", "true"));

    Request request =
        new Request.Builder().put(RequestBody.create(null, body)).url(urlBuilder.build()).build();

    safeCall(request);
  }

  @Override
  public void removeObject(String bucketName, String objectKey) {
    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(endpoint)
            .newBuilder()
            .addPathSegment("bucket")
            .query("object")
            .addQueryParameter("bucket", bucketName)
            .addQueryParameter("object", objectKey);

    Request request = new Request.Builder().delete().url(urlBuilder.build()).build();

    safeCall(request);
  }

  /**
   * Read the policy of an object or bucket.
   *
   * <p>Note that the term "policy" here is not stand for "S3 bucket policy". It represents S3
   * Access Control Policy (ACP).
   *
   * <p>We return String instead of the concrete model here due to the server returns not well
   * defined internal data structure. For example:
   *
   * <pre>
   * {"acl":{"acl_user_map":[{"user":"rgwAdmin4jTest-6d6a2645-0219-4e49-8493-0bdc8cb00e19","acl":15}],"acl_group_map":[],"grant_map":[{"id":"rgwAdmin4jTest-6d6a2645-0219-4e49-8493-0bdc8cb00e19","grant":{"
   * type":{"type":0},"id":"rgwAdmin4jTest-6d6a2645-0219-4e49-8493-0bdc8cb00e19","email":"","permission":{"flags":15},"name":"rgwAdmin4jTest-6d6a2645-0219-4e49-8493-0bdc8cb00e19","group":0,"url_spec":""}}
   * ]},"owner":{"id":"rgwAdmin4jTest-6d6a2645-0219-4e49-8493-0bdc8cb00e19","display_name":"rgwAdmin4jTest-6d6a2645-0219-4e49-8493-0bdc8cb00e19"}}
   * </pre>
   *
   * @param bucketName
   * @param objectKey set null if you want to get policy of bucket
   * @return json string
   */
  @Override
  public String getPolicy(String bucketName, String objectKey) {
    if (Strings.isNullOrEmpty(bucketName)) {
      throw new IllegalArgumentException("no bucketName");
    }

    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(endpoint)
            .newBuilder()
            .addPathSegment("bucket")
            .query("policy")
            .addQueryParameter("bucket", bucketName);

    if (!Strings.isNullOrEmpty(objectKey)) {
      urlBuilder.addQueryParameter("object", objectKey);
    }

    Request request = new Request.Builder().get().url(urlBuilder.build()).build();

    return safeCall(request);
  }
}
