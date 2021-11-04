/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.graphql.data.querydsl;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.querydsl.core.types.Predicate;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLTypeVisitor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.keyvalue.core.KeyValueTemplate;
import org.springframework.data.keyvalue.repository.support.KeyValueRepositoryFactory;
import org.springframework.data.map.MapKeyValueAdapter;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.querydsl.ReactiveQuerydslPredicateExecutor;
import org.springframework.data.querydsl.binding.QuerydslBinderCustomizer;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.graphql.Author;
import org.springframework.graphql.BookSource;
import org.springframework.graphql.GraphQlTestUtils;
import org.springframework.graphql.data.GraphQlRepository;
import org.springframework.graphql.execution.ExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.web.WebGraphQlHandler;
import org.springframework.graphql.web.WebInput;
import org.springframework.graphql.web.WebOutput;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuerydslDataFetcher}.
 */
class QuerydslDataFetcherTests {

	private final KeyValueRepositoryFactory repositoryFactory =
			new KeyValueRepositoryFactory(new KeyValueTemplate(new MapKeyValueAdapter()));

	private final MockRepository mockRepository = repositoryFactory.getRepository(MockRepository.class);


	@Test
	void shouldFetchSingleItems() {
		Book book = new Book(42L, "Hitchhiker's Guide to the Galaxy", new Author(0L, "Douglas", "Adams"));
		mockRepository.save(book);

		Consumer<WebGraphQlHandler> tester = (handler) -> {
			WebOutput output = handler.handleRequest(input("{ bookById(id: 42) {name}}")).block();

			Map<String, Object> map = GraphQlTestUtils.getData(output, "bookById");
			assertThat(map).hasSize(1).containsEntry("name", book.getName());
		};

		// explicit wiring
		tester.accept(initHandler("bookById", QuerydslDataFetcher.builder(mockRepository).single()));

		// auto registration
		tester.accept(initHandler(builder -> {}, mockRepository, null));
	}

	@Test
	void shouldFetchMultipleItems() {
		Book book1 = new Book(42L, "Hitchhiker's Guide to the Galaxy", new Author(0L, "Douglas", "Adams"));
		Book book2 = new Book(53L, "Breaking Bad", new Author(0L, "", "Heisenberg"));
		mockRepository.saveAll(Arrays.asList(book1, book2));

		Consumer<WebGraphQlHandler> tester = (handler) -> {
			WebOutput output = handler.handleRequest(input("{ books {name}}")).block();

			List<Map<String, Object>> data = GraphQlTestUtils.getData(output, "books");
			assertThat(data).containsExactlyInAnyOrder(
					Collections.singletonMap("name", "Breaking Bad"),
					Collections.singletonMap("name", "Hitchhiker's Guide to the Galaxy"));
		};

		// explicit wiring
		tester.accept(initHandler("books", QuerydslDataFetcher.builder(mockRepository).many()));

		// auto registration
		tester.accept(initHandler(builder -> {}, mockRepository, null));
	}

	@Test
	void shouldFavorExplicitWiring() {
		MockRepository mockRepository = mock(MockRepository.class);
		Book book = new Book(42L, "Hitchhiker's Guide to the Galaxy", new Author(0L, "Douglas", "Adams"));
		when(mockRepository.findBy(any(), any())).thenReturn(Optional.of(book));

		// 1) Automatic registration only
		WebGraphQlHandler handler = initHandler(builder -> {}, mockRepository, null);
		WebOutput output = handler.handleRequest(input("{ bookById(id: 1) {name}}")).block();

		Map<String, Object> map = GraphQlTestUtils.getData(output, "bookById");
		assertThat(map).hasSize(1).containsEntry("name", "Hitchhiker's Guide to the Galaxy");

		// 2) Automatic registration and explicit wiring
		handler = initHandler(
				"bookById", env -> new Book(53L, "Breaking Bad", new Author(0L, "", "Heisenberg")),
				mockRepository);

		output = handler.handleRequest(input("{ bookById(id: 1) {name}}")).block();

		map = GraphQlTestUtils.getData(output, "bookById");
		assertThat(map).hasSize(1).containsEntry("name", "Breaking Bad");
	}

	@Test
	void shouldFetchSingleItemsWithInterfaceProjection() {
		Book book = new Book(42L, "Hitchhiker's Guide to the Galaxy", new Author(0L, "Douglas", "Adams"));
		mockRepository.save(book);

		WebGraphQlHandler handler = initHandler("bookById",
				QuerydslDataFetcher.builder(mockRepository).projectAs(BookProjection.class).single());

		WebOutput output = handler.handleRequest(input("{ bookById(id: 42) {name}}")).block();

		Map<String, Object> map = GraphQlTestUtils.getData(output, "bookById");
		assertThat(map).hasSize(1).containsEntry("name", "Hitchhiker's Guide to the Galaxy by Douglas Adams");
	}

	@Test
	void shouldFetchSingleItemsWithDtoProjection() {
		Book book = new Book(42L, "Hitchhiker's Guide to the Galaxy", new Author(0L, "Douglas", "Adams"));
		mockRepository.save(book);

		WebGraphQlHandler handler = initHandler("bookById",
				QuerydslDataFetcher.builder(mockRepository).projectAs(BookDto.class).single());

		WebOutput output = handler.handleRequest(input("{ bookById(id: 42) {name}}")).block();

		Map<String, Object> map = GraphQlTestUtils.getData(output, "bookById");
		assertThat(map).hasSize(1).containsEntry("name", "The book is: Hitchhiker's Guide to the Galaxy");
	}

	@Test
	void shouldConstructPredicateProperly() {
		MockRepository mockRepository = mock(MockRepository.class);

		WebGraphQlHandler handler = initHandler("books",
				QuerydslDataFetcher.builder(mockRepository)
						.customizer((QuerydslBinderCustomizer<QBook>) (bindings, book) ->
								bindings.bind(book.name).firstOptional((path, value) -> value.map(path::startsWith)))
						.many());

		handler.handleRequest(input("{ books(name: \"H\", author: \"Doug\") {name}}")).block();

		ArgumentCaptor<Predicate> predicateCaptor = ArgumentCaptor.forClass(Predicate.class);
		verify(mockRepository).findBy(predicateCaptor.capture(), any());

		Predicate predicate = predicateCaptor.getValue();
		assertThat(predicate).isEqualTo(QBook.book.name.startsWith("H").and(QBook.book.author.eq("Doug")));
	}

	@Test
	void shouldReactivelyFetchSingleItems() {
		ReactiveMockRepository mockRepository = mock(ReactiveMockRepository.class);
		Book book = new Book(42L, "Hitchhiker's Guide to the Galaxy", new Author(0L, "Douglas", "Adams"));
		when(mockRepository.findBy(any(), any())).thenReturn(Mono.just(book));

		Consumer<WebGraphQlHandler> tester = (handler) -> {
			WebOutput output = handler.handleRequest(input("{ bookById(id: 1) {name}}")).block();

			Map<String, Object> map = GraphQlTestUtils.getData(output, "bookById");
			assertThat(map).hasSize(1).containsEntry("name", book.getName());
		};

		// explicit wiring
		tester.accept(initHandler("bookById", QuerydslDataFetcher.builder(mockRepository).single()));

		// auto registration
		tester.accept(initHandler(builder -> {}, null, mockRepository));
	}

	@Test
	void shouldReactivelyFetchMultipleItems() {
		ReactiveMockRepository mockRepository = mock(ReactiveMockRepository.class);
		Book book1 = new Book(42L, "Hitchhiker's Guide to the Galaxy", new Author(0L, "Douglas", "Adams"));
		Book book2 = new Book(53L, "Breaking Bad", new Author(0L, "", "Heisenberg"));
		when(mockRepository.findBy(any(), any())).thenReturn(Flux.just(book1, book2));

		Consumer<WebGraphQlHandler> tester = (handler) -> {
			WebOutput output = handler.handleRequest(input("{ books {name}}")).block();

			List<Map<String, Object>> data = GraphQlTestUtils.getData(output, "books");
			assertThat(data).containsExactlyInAnyOrder(
					Collections.singletonMap("name", "Breaking Bad"),
					Collections.singletonMap("name", "Hitchhiker's Guide to the Galaxy"));
		};

		// explicit wiring
		tester.accept(initHandler("books", QuerydslDataFetcher.builder(mockRepository).many()));

		// auto registration
		tester.accept(initHandler(builder -> {}, null, mockRepository));
	}


	@GraphQlRepository
	interface MockRepository extends CrudRepository<Book, Long>, QuerydslPredicateExecutor<Book> {

	}

	@GraphQlRepository
	interface ReactiveMockRepository extends Repository<Book, Long>, ReactiveQuerydslPredicateExecutor<Book> {

	}

	static WebGraphQlHandler initHandler(String fieldName, DataFetcher<?> fetcher) {
		return initHandler(fieldName, fetcher, null);
	}

	static WebGraphQlHandler initHandler(
			String fieldName, DataFetcher<?> fetcher, @Nullable QuerydslPredicateExecutor<?> executor) {

		return initHandler(
				GraphQlTestUtils.graphQlSource(BookSource.schema, "Query", fieldName, fetcher),
				executor, null);
	}

	static WebGraphQlHandler initHandler(
			RuntimeWiringConfigurer configurer,
			@Nullable QuerydslPredicateExecutor<?> executor,
			@Nullable ReactiveQuerydslPredicateExecutor<?> reactiveExecutor) {

		return initHandler(
				GraphQlTestUtils.graphQlSource(BookSource.schema, configurer),
				executor,
				reactiveExecutor);
	}

	private static WebGraphQlHandler initHandler(
			GraphQlSource.Builder sourceBuilder,
			@Nullable QuerydslPredicateExecutor<?> executor,
			@Nullable ReactiveQuerydslPredicateExecutor<?> reactiveExecutor) {

		GraphQLTypeVisitor visitor = QuerydslDataFetcher.registrationTypeVisitor(
				(executor != null ? Collections.singletonList(executor) : Collections.emptyList()),
				(reactiveExecutor != null ? Collections.singletonList(reactiveExecutor) : Collections.emptyList()));

		GraphQlSource source = sourceBuilder.typeVisitors(Collections.singletonList(visitor)).build();
		ExecutionGraphQlService service = new ExecutionGraphQlService(source);
		return WebGraphQlHandler.builder(service).build();
	}

	private WebInput input(String query) {
		return new WebInput(URI.create("/"), new HttpHeaders(), Collections.singletonMap("query", query), null, "1");
	}


	interface BookProjection {

		@Value("#{target.name + ' by ' + target.author.firstName + ' ' + target.author.lastName}")
		String getName();

	}

	static class BookDto {

		private final String name;

		public BookDto(String name) {
			this.name = name;
		}

		public String getName() {
			return "The book is: " + name;
		}

	}

}
