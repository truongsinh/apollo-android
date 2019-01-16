package com.apollographql.apollo.internal.interceptor;

import com.apollographql.apollo.api.GraphqlUpload;
import com.apollographql.apollo.api.Operation;
import com.apollographql.apollo.api.internal.Optional;
import com.apollographql.apollo.api.cache.http.HttpCache;
import com.apollographql.apollo.api.cache.http.HttpCachePolicy;
import com.apollographql.apollo.cache.ApolloCacheHeaders;
import com.apollographql.apollo.cache.CacheHeaders;
import com.apollographql.apollo.exception.ApolloNetworkException;
import com.apollographql.apollo.interceptor.ApolloInterceptor;
import com.apollographql.apollo.interceptor.ApolloInterceptorChain;
import com.apollographql.apollo.internal.json.InputFieldJsonWriter;
import com.apollographql.apollo.internal.json.JsonWriter;
import com.apollographql.apollo.response.ScalarTypeAdapters;
import com.apollographql.apollo.internal.ApolloLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.ByteString;

import static com.apollographql.apollo.api.internal.Utils.checkNotNull;

/**
 * ApolloServerInterceptor is a concrete {@link ApolloInterceptor} responsible for making the network calls to the
 * server. It is the last interceptor in the chain of interceptors and hence doesn't call {@link
 * ApolloInterceptorChain#proceed(FetchOptions)} on the interceptor chain.
 */
@SuppressWarnings("WeakerAccess") public final class ApolloServerInterceptor implements ApolloInterceptor {
  static final String HEADER_ACCEPT_TYPE = "Accept";
  static final String HEADER_CONTENT_TYPE = "Content-Type";
  static final String HEADER_APOLLO_OPERATION_ID = "X-APOLLO-OPERATION-ID";
  static final String HEADER_APOLLO_OPERATION_NAME = "X-APOLLO-OPERATION-NAME";
  static final String ACCEPT_TYPE = "application/json";
  static final String CONTENT_TYPE = "application/json";
  static final MediaType MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

  final HttpUrl serverUrl;
  final okhttp3.Call.Factory httpCallFactory;
  final Optional<HttpCachePolicy.Policy> cachePolicy;
  final boolean prefetch;
  final ApolloLogger logger;
  final ScalarTypeAdapters scalarTypeAdapters;
  volatile Call httpCall;
  volatile boolean disposed;

  public ApolloServerInterceptor(@NotNull HttpUrl serverUrl, @NotNull Call.Factory httpCallFactory,
      @Nullable HttpCachePolicy.Policy cachePolicy, boolean prefetch,
      @NotNull ScalarTypeAdapters scalarTypeAdapters, @NotNull ApolloLogger logger) {
    this.serverUrl = checkNotNull(serverUrl, "serverUrl == null");
    this.httpCallFactory = checkNotNull(httpCallFactory, "httpCallFactory == null");
    this.cachePolicy = Optional.fromNullable(cachePolicy);
    this.prefetch = prefetch;
    this.scalarTypeAdapters = checkNotNull(scalarTypeAdapters, "scalarTypeAdapters == null");
    this.logger = checkNotNull(logger, "logger == null");
  }

  @Override
  public void interceptAsync(@NotNull final InterceptorRequest request, @NotNull final ApolloInterceptorChain chain,
      @NotNull Executor dispatcher, @NotNull final CallBack callBack) {
    if (disposed) return;
    dispatcher.execute(new Runnable() {
      @Override public void run() {
        callBack.onFetch(FetchSourceType.NETWORK);

        try {
          httpCall = httpCall(request.operation, request.cacheHeaders, request.sendQueryDocument);
        } catch (IOException e) {
          logger.e(e, "Failed to prepare http call for operation %s", request.operation.name().name());
          callBack.onFailure(new ApolloNetworkException("Failed to prepare http call", e));
          return;
        }

        httpCall.enqueue(new Callback() {
          @Override public void onFailure(@NotNull Call call, @NotNull IOException e) {
            if (disposed) return;
            logger.e(e, "Failed to execute http call for operation %s", request.operation.name().name());
            callBack.onFailure(new ApolloNetworkException("Failed to execute http call", e));
          }

          @Override public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
            if (disposed) return;
            callBack.onResponse(new ApolloInterceptor.InterceptorResponse(response));
            callBack.onCompleted();
          }
        });
      }
    });
  }

  @Override public void dispose() {
    disposed = true;
    Call httpCall = this.httpCall;
    if (httpCall != null) {
      httpCall.cancel();
    }
    this.httpCall = null;
  }

  Call httpCall(Operation operation, CacheHeaders cacheHeaders, boolean writeQueryDocument) throws IOException {
    RequestBody requestBody = RequestBody.create(MEDIA_TYPE, httpRequestBody(operation, scalarTypeAdapters,
        writeQueryDocument));
    Request.Builder requestBuilder = new Request.Builder()
        .url(serverUrl)
        .post(requestBody)
        .header(HEADER_ACCEPT_TYPE, ACCEPT_TYPE)
        .header(HEADER_CONTENT_TYPE, CONTENT_TYPE)
        .header(HEADER_APOLLO_OPERATION_ID, operation.operationId())
        .header(HEADER_APOLLO_OPERATION_NAME, operation.name().name())
        .tag(operation.operationId());

    if (cachePolicy.isPresent()) {
      HttpCachePolicy.Policy cachePolicy = this.cachePolicy.get();
      boolean skipCacheHttpResponse = "true".equalsIgnoreCase(cacheHeaders.headerValue(
          ApolloCacheHeaders.DO_NOT_STORE));

      String cacheKey = httpRequestBody(operation, scalarTypeAdapters, true).md5().hex();
      requestBuilder = requestBuilder
          .header(HttpCache.CACHE_KEY_HEADER, cacheKey)
          .header(HttpCache.CACHE_FETCH_STRATEGY_HEADER, cachePolicy.fetchStrategy.name())
          .header(HttpCache.CACHE_EXPIRE_TIMEOUT_HEADER, String.valueOf(cachePolicy.expireTimeoutMs()))
          .header(HttpCache.CACHE_EXPIRE_AFTER_READ_HEADER, Boolean.toString(cachePolicy.expireAfterRead))
          .header(HttpCache.CACHE_PREFETCH_HEADER, Boolean.toString(prefetch))
          .header(HttpCache.CACHE_DO_NOT_STORE, Boolean.toString(skipCacheHttpResponse));
    }

    return httpCallFactory.newCall(requestBuilder.build());
  }

  static ByteString httpRequestBody(Operation operation, ScalarTypeAdapters scalarTypeAdapters,
      boolean writeQueryDocument) throws IOException {

  // private RequestBody httpRequestBody(Operation operation) throws IOException {

    ArrayList<GraphqlUpload> allUploads = new ArrayList<>();
    HashMap<String, String[]> filesMap = new HashMap<>();
    int fileIndex = 0;
    for (String variableName : operation.variables().valueMap().keySet()) {
      Object value = operation.variables().valueMap().get(variableName);
      if (value instanceof GraphqlUpload) {
        GraphqlUpload upload = (GraphqlUpload)value;
        allUploads.add(upload);
        filesMap.put("" + fileIndex, new String[] { "variables." + variableName });
        fileIndex++;
      }
      else if (value instanceof  GraphqlUpload[]) {
        int varFileIndex = 0;
        GraphqlUpload[] uploads = (GraphqlUpload[])value;
        for (GraphqlUpload upload: uploads) {
          allUploads.add(upload);
          filesMap.put("" + fileIndex, new String[] { "variables." + variableName + "." + varFileIndex });
          varFileIndex++;
          fileIndex++;
        }
      }
    }

    Buffer buffer = new Buffer();
    JsonWriter jsonWriter = JsonWriter.of(buffer);
    jsonWriter.setSerializeNulls(true);
    jsonWriter.beginObject();
    jsonWriter.name("operationName").value(operation.name().name());
    jsonWriter.name("variables").beginObject();
    operation.variables().marshaller().marshal(new InputFieldJsonWriter(jsonWriter, scalarTypeAdapters));
    jsonWriter.endObject();
    jsonWriter.name("extensions")
        .beginObject()
        .name("persistedQuery")
        .beginObject()
        .name("version").value(1)
        .name("sha256Hash").value(operation.operationId())
        .endObject()
        .endObject();
    if (writeQueryDocument) {
      jsonWriter.name("query").value(operation.queryDocument().replaceAll("\\n", ""));
    }
    jsonWriter.endObject();
    jsonWriter.close();
    // return RequestBody.create(MEDIA_TYPE, buffer.readByteString());
    // return buffer.readByteString();
    RequestBody mainBody = RequestBody.create(MEDIA_TYPE, buffer.readByteString());
    if (allUploads.isEmpty()) {
      return mainBody;
    } else {
      return httpMultipartRequestBody(mainBody, filesMap, allUploads.toArray(new GraphqlUpload[0]));
    }
  }

  private RequestBody httpMultipartRequestBody(RequestBody operations, HashMap<String, String[]> filesMap, GraphqlUpload[] uploads) throws IOException {
    Buffer buffer = new Buffer();
    JsonWriter jsonWriter = JsonWriter.of(buffer);
    jsonWriter.setSerializeNulls(true);
    jsonWriter.beginObject();
    for (String key: filesMap.keySet()) {
      jsonWriter.name(key).beginArray();
      jsonWriter.value((filesMap.get(key))[0]);
      jsonWriter.endArray();
    }
    jsonWriter.endObject();
    jsonWriter.close();
    MultipartBody.Builder multipartBodyBuilder = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("operations", null, operations)
            .addFormDataPart("map", null, RequestBody.create(MEDIA_TYPE, buffer.readByteString()));
    int index = 0;
    for (GraphqlUpload upload: uploads) {
      multipartBodyBuilder.addFormDataPart("" + index, upload.file.getName(),
              RequestBody.create(MediaType.parse(upload.mimetype), upload.file));
    }
    return multipartBodyBuilder.build();
  }

  public static String cacheKey(RequestBody requestBody) {
    Buffer hashBuffer = new Buffer();
    try {
      requestBody.writeTo(hashBuffer);
    } catch (IOException e) {
      // should never happen
      throw new RuntimeException(e);
    }
    return hashBuffer.readByteString().md5().hex();
  }
}
