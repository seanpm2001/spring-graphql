/*
 * Copyright 2020-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.graphql.server.webmvc;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.GraphQlRequest;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.support.SerializableGraphQlRequest;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.AlternativeJdkIdGenerator;
import org.springframework.util.Assert;
import org.springframework.util.IdGenerator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * GraphQL handler to expose as a WebMvc functional endpoint via
 * {@link org.springframework.web.servlet.function.RouterFunctions}.
 *
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @since 1.0.0
 */
public class GraphQlHttpHandler {

	private static final Log logger = LogFactory.getLog(GraphQlHttpHandler.class);

	private static final ParameterizedTypeReference<Map<String, Object>> MAP_PARAMETERIZED_TYPE_REF =
			new ParameterizedTypeReference<>() {

			};

	// To be removed in favor of Framework's MediaType.APPLICATION_GRAPHQL_RESPONSE
	private static final MediaType APPLICATION_GRAPHQL_RESPONSE =
			new MediaType("application", "graphql-response+json");

	@SuppressWarnings("removal")
	private static final List<MediaType> SUPPORTED_MEDIA_TYPES =
			Arrays.asList(APPLICATION_GRAPHQL_RESPONSE, MediaType.APPLICATION_JSON, MediaType.APPLICATION_GRAPHQL);

	private final IdGenerator idGenerator = new AlternativeJdkIdGenerator();

	private final WebGraphQlHandler graphQlHandler;

	/**
	 * Create a new instance.
	 * @param graphQlHandler common handler for GraphQL over HTTP requests
	 */
	public GraphQlHttpHandler(WebGraphQlHandler graphQlHandler) {
		Assert.notNull(graphQlHandler, "WebGraphQlHandler is required");
		this.graphQlHandler = graphQlHandler;
	}

	/**
	 * Handle GraphQL requests over HTTP.
	 * @param serverRequest the incoming HTTP request
	 * @return the HTTP response
	 * @throws ServletException may be raised when reading the request body, e.g.
	 * {@link HttpMediaTypeNotSupportedException}.
	 */
	public ServerResponse handleRequest(ServerRequest serverRequest) throws ServletException {

		WebGraphQlRequest graphQlRequest = new WebGraphQlRequest(
				serverRequest.uri(), serverRequest.headers().asHttpHeaders(), initCookies(serverRequest),
				serverRequest.attributes(), readBody(serverRequest), this.idGenerator.generateId().toString(),
				LocaleContextHolder.getLocale());

		if (logger.isDebugEnabled()) {
			logger.debug("Executing: " + graphQlRequest);
		}

		CompletableFuture<ServerResponse> future = this.graphQlHandler.handleRequest(graphQlRequest)
				.map((response) -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Execution complete");
					}
					ServerResponse.BodyBuilder builder = ServerResponse.ok();
					builder.headers((headers) -> headers.putAll(response.getResponseHeaders()));
					builder.contentType(selectResponseMediaType(serverRequest));
					return builder.body(response.toMap());
				})
				.toFuture();

		if (future.isDone()) {
			try {
				return future.get();
			}
			catch (ExecutionException ex) {
				throw new ServletException(ex.getCause());
			}
			catch (InterruptedException ex) {
				throw new ServletException(ex);
			}
		}

		return ServerResponse.async(future);
	}

	private static MultiValueMap<String, HttpCookie> initCookies(ServerRequest serverRequest) {
		MultiValueMap<String, Cookie> source = serverRequest.cookies();
		MultiValueMap<String, HttpCookie> target = new LinkedMultiValueMap<>(source.size());
		source.values().forEach((cookieList) -> cookieList.forEach((cookie) -> {
			HttpCookie httpCookie = new HttpCookie(cookie.getName(), cookie.getValue());
			target.add(cookie.getName(), httpCookie);
		}));
		return target;
	}

	private static GraphQlRequest readBody(ServerRequest request) throws ServletException {
		try {
			return request.body(SerializableGraphQlRequest.class);
		}
		catch (IOException ex) {
			throw new ServerWebInputException("I/O error while reading request body", null, ex);
		}
		catch (HttpMediaTypeNotSupportedException ex) {
			return applyApplicationGraphQlFallback(request, ex);
		}
	}

	private static SerializableGraphQlRequest applyApplicationGraphQlFallback(
			ServerRequest request, HttpMediaTypeNotSupportedException ex) throws HttpMediaTypeNotSupportedException {
		String contentTypeHeader = request.headers().firstHeader(HttpHeaders.CONTENT_TYPE);
		if (StringUtils.hasText(contentTypeHeader)) {
			MediaType contentType = MediaType.parseMediaType(contentTypeHeader);
			MediaType applicationGraphQl = MediaType.parseMediaType("application/graphql");
			// Spec requires application/json but some clients still use application/graphql
			if (applicationGraphQl.includes(contentType)) {
				try {
					request = ServerRequest.from(request)
							.headers((headers) -> headers.setContentType(MediaType.APPLICATION_JSON))
							.body(request.body(byte[].class))
							.build();
					return request.body(SerializableGraphQlRequest.class);
				}
				catch (Throwable ex2) {
					// ignore
				}
			}
		}
		throw ex;
	}


	private static MediaType selectResponseMediaType(ServerRequest serverRequest) {
		for (MediaType accepted : serverRequest.headers().accept()) {
			if (SUPPORTED_MEDIA_TYPES.contains(accepted)) {
				return accepted;
			}
		}
		return MediaType.APPLICATION_JSON;
	}

}
