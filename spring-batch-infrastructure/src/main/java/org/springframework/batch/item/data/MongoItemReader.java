/*
 * Copyright 2012 the original author or authors.
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

package org.springframework.batch.item.data;

import com.mongodb.util.JSON;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * Restartable {@link ItemReader} that reads documents from MongoDB
 * via a paging technique.
 * </p>
 *
 * <p>
 * It executes the JSON {@link #setQuery(String)} to retrieve the requested
 * documents.  The query is executed using paged requests specified in the
 * {@link #setPageSize(int)}.  Additional pages are requested as needed to
 * provide data when the {@link #read()} method is called.
 * </p>
 *
 * <p>
 * The JSON query provided supports parameter substitution via ?&lt;index&gt;
 * placeholders where the &lt;index&gt; indicates the index of the
 * parameterValue to substitute.
 * </p>
 *
 * <p>
 * The implementation is thread-safe between calls to
 * {@link #open(ExecutionContext)}, but remember to use <code>saveState=false</code>
 * if used in a multi-threaded client (no restart available).
 * </p>
 *
 *
 * @author Michael Minella
 */
public class MongoItemReader<T> extends AbstractPaginatedDataItemReader<T> implements InitializingBean {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\?(\\d+)");
	private MongoOperations template;
	private String query;
	private Class<? extends T> type;
	private Sort sort;
	private String hint;
	private String fields;
	private String collection;
	private List<Object> parameterValues;
	private Object lock = new Object();

	public MongoItemReader() {
		super();
		setName(ClassUtils.getShortName(MongoItemReader.class));
	}

	/**
	 * Used to perform operations against the MongoDB instance.  Also
	 * handles the mapping of documents to objects.
	 *
	 * @param template the MongoOperations instance to use
	 * @see MongoOperations
	 */
	public void setTemplate(MongoOperations template) {
		this.template = template;
	}

	/**
	 * A JSON formatted MongoDB query.  Parameterization of the provided query is allowed
	 * via ?&lt;index&gt; placeholders where the &lt;index&gt; indicates the index of the
	 * parameterValue to substitute.
	 *
	 * @param query JSON formatted Mongo query
	 */
	public void setQuery(String query) {
		this.query = query;
	}

	/**
	 * The type of object to be returned for each {@link #read()} call.
	 *
	 * @param type the type of object to return
	 */
	public void setTargetType(Class<? extends T> type) {
		this.type = type;
	}

	/**
	 * {@link List} of values to be substituted in for each of the
	 * parameters in the query.
	 *
	 * @param parameterValues
	 */
	public void setParameterValues(List<Object> parameterValues) {
		this.parameterValues = parameterValues;
	}

	/**
	 * JSON defining the fields to be returned from the matching documents
	 * by MongoDB.
	 *
	 * @param fields JSON string that identifies the fields to sort by.
	 */
	public void setFields(String fields) {
		this.fields = fields;
	}

	/**
	 * {@link Map} of property names/{@link org.springframework.data.domain.Sort.Direction} values to
	 * sort the input by.
	 *
	 * @param sorts map of properties and direction to sort each.
	 */
	public void setSort(Map<String, Sort.Direction> sorts) {
		this.sort = convertToSort(sorts);
	}

	/**
	 * @param collection Mongo collection to be queried.
	 */
	public void setCollection(String collection) {
		this.collection = collection;
	}

	/**
	 * JSON String telling MongoDB what index to use.
	 *
	 * @param hint string indicating what index to use.
	 */
	public void setHint(String hint) {
		this.hint = hint;
	}

	@Override
	@SuppressWarnings("unchecked")
	protected Iterator<T> doPageRead() {

		Pageable pageRequest = new PageRequest(page, pageSize, sort);

		String populatedQuery = replacePlaceholders(query, parameterValues);

		Query mongoQuery = null;

		if(StringUtils.hasText(fields)) {
			mongoQuery = new BasicQuery(populatedQuery, fields);
		}
		else {
			mongoQuery = new BasicQuery(populatedQuery);
		}

		mongoQuery.with(pageRequest);

		if(StringUtils.hasText(hint)) {
			mongoQuery.withHint(hint);
		}

		if(StringUtils.hasText(collection)) {
			return (Iterator<T>) template.find(mongoQuery, type, collection).iterator();
		} else {
			return (Iterator<T>) template.find(mongoQuery, type).iterator();
		}
	}

	@Override
	protected void doClose() throws Exception {
		synchronized (lock) {
			page = 0;
			results = null;
		}
	}

	/**
	 * Checks mandatory properties
	 *
	 * @see InitializingBean#afterPropertiesSet()
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
		Assert.state(template != null, "An implementation of MongoOperations is required.");
		Assert.state(type != null, "A type to convert the input into is required.");
		Assert.state(query != null, "A query is required.");
		Assert.state(sort != null, "A sort is required.");
	}

	// Copied from StringBasedMongoQuery...is there a place where this type of logic is already exposed?
	private String replacePlaceholders(String input, List<Object> values) {
		Matcher matcher = PLACEHOLDER.matcher(input);
		String result = input;

		while (matcher.find()) {
			String group = matcher.group();
			int index = Integer.parseInt(matcher.group(1));
			result = result.replace(group, getParameterWithIndex(values, index));
		}

		return result;
	}

	// Copied from StringBasedMongoQuery...is there a place where this type of logic is already exposed?
	private String getParameterWithIndex(List<Object> values, int index) {
		return JSON.serialize(values.get(index));
	}

	private Sort convertToSort(Map<String, Sort.Direction> sorts) {
		List<Sort.Order> sortValues = new ArrayList<Sort.Order>();

		for (Map.Entry<String, Sort.Direction> curSort : sorts.entrySet()) {
			sortValues.add(new Sort.Order(curSort.getValue(), curSort.getKey()));
		}

		return new Sort(sortValues);
	}
}
