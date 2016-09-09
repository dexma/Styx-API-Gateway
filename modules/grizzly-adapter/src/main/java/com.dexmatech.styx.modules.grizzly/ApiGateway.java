package com.dexmatech.styx.modules.grizzly;

import com.dexmatech.styx.core.ApiPipeline;
import com.dexmatech.styx.core.http.HttpResponse;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.threadpool.GrizzlyExecutorService;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Created by aortiz on 12/08/16.
 */
@Slf4j
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiGateway {

	public static final int DEFAULT_PORT = 8080;
	public static final Supplier<ExecutorService> DEFAULT_EXECUTOR_SERVICE_SUPPLIER = () ->
			GrizzlyExecutorService.createInstance(
					ThreadPoolConfig.defaultConfig()
							.copy()
							.setCorePoolSize(5)
							.setMaxPoolSize(5)
			);

	private final HttpServer httpServer;
	private final ExecutorService executorService;
	private final ApiPipeline pipeline;

	public static Builder runningOverGrizzly() {
		return new Builder();
	}

	public static class Builder {
		private Optional<HttpServer> httpServer = Optional.empty();//HttpServer.createSimpleServer();
		private Optional<ExecutorService> complexAppExecutorService = Optional.empty();
		private int port = DEFAULT_PORT;
		private ApiPipeline pipeline;

		public Builder providingHttpServer(HttpServer httpServer) {
			this.httpServer = Optional.ofNullable(httpServer);
			return this;
		}

		public Builder withExecutorService(ExecutorService complexAppExecutorService) {
			this.complexAppExecutorService = Optional.ofNullable(complexAppExecutorService);
			return this;
		}

		public Builder withDefaultServerRunningOnPort(int port) {
			this.port = port;
			return this;
		}

		public Builder withPipeline(ApiPipeline pipeline) {
			this.pipeline = pipeline;
			return this;
		}

		public ApiGateway build() {
			Objects.requireNonNull(pipeline, "Api pipeline can not be empty");
			log.info("[API Gateway on Grizzly] was successfully created");
			return new ApiGateway(
					httpServer.orElse(HttpServer.createSimpleServer(".", port)),
					complexAppExecutorService.orElseGet(DEFAULT_EXECUTOR_SERVICE_SUPPLIER),
					pipeline

			);
		}
	}

	public void start(){
		this.run(false);
	}

	public void startAndKeepRunning(){
		this.run(true);
	}

	private ApiGateway run(boolean keepRunning) {
		httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {

			@Override
			public void service(final Request request, final Response response) throws Exception {

				response.suspend(); // Instruct Grizzly to not flush response, once we exit the service(...) method
				log.debug("[Grizzly adapter] Handling request and dispatching to pipeline '{}'",request.getRequest());
				executorService.execute(() -> {
					try {
						//						log.info(request.toString());
						CompletableFuture<HttpResponse> applyHttpReplyProtocol = pipeline.reply(RequestResponseMappers.asPipelineRequest(request));
						applyHttpReplyProtocol
								.thenAccept(httpResponse -> {
									try {
										RequestResponseMappers.mapResponseFields(httpResponse, response);
										response.resume();
									} catch (Throwable e) {
										handleError(response, e);
									}
								});
					} catch (Throwable e) {
						handleError(response, e);
					}

				});
			}
		}, "/*");
		try {
			httpServer.start();
			if(keepRunning) {
				Thread.currentThread().join();
			}
		} catch (Exception e) {
			log.error("Grizzly server not loaded", e);
		}
		return this;
	}


	public void shutdown(){
		httpServer.shutdownNow();
	}

	private void handleError(Response response, Throwable e) {
		log.error("REQUEST NOT ATTENDED", e);
		response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
		response.resume();  // Finishing HTTP request processing and flushing the response to the client
	}
}
