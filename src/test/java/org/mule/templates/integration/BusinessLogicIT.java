/**
 * Mule Anypoint Template
 * Copyright (c) MuleSoft, Inc.
 * All rights reserved.  http://www.mulesoft.com
 */
package org.mule.templates.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleException;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.construct.Flow;
import org.mule.templates.AbstractTemplatesTestCase;
import org.mule.transport.NullPayload;

import com.mulesoft.module.batch.BatchTestHelper;

/**
 * The objective of this class is validating the correct behavior of the flows
 * for this Mule Anypoint Template
 * 
 */
@SuppressWarnings("unchecked")
public class BusinessLogicIT extends AbstractTemplatesTestCase {

	private static final String SAP2SFDC_INBOUND_FLOW_NAME = "fromSapToSalesforceFlow";
	private static final int TIMEOUT_MILLIS = 120;
	private static final int OFFSET_BETWEEN_SFDC_AND_SAP = (- 5 * 60 * 60000) - 60000; // - five hours and one minute

	private List<String> productsCreatedInSalesforce;
	private List<String> productsCreatedInSap;
	
	private Flow deleteProductFromSalesforceFlow;
	private Flow deleteProductFromSapFlow;
	private Flow createProductInSapFlow;
	private Flow queryProductFromSalesforceFlow;
	private Flow queryProductFromSapFlow;
	private BatchTestHelper batchTestHelper;

	@BeforeClass
	public static void beforeTestClass() {
		// Set default water-mark  expression to current time
		DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(DateTimeZone.UTC);
		System.setProperty("watermark.default.expression", formatter.print(System.currentTimeMillis() + OFFSET_BETWEEN_SFDC_AND_SAP));
	}

	@Before
	public void setUp() throws MuleException {
		productsCreatedInSalesforce = new ArrayList<String>();
		productsCreatedInSap = new ArrayList<String>();
		
		getAndInitializeFlows();
		
		batchTestHelper = new BatchTestHelper(muleContext);
	}

	@After
	public void tearDown() throws MuleException, Exception {
		cleanUpSandboxesByRemovingTestProducts();
		productsCreatedInSalesforce = null;
		productsCreatedInSap = null;
	}

	private void getAndInitializeFlows() throws InitialisationException {
		// Flow for creating products in Sap
		createProductInSapFlow = getFlow("createProductsInSapFlow");

		// Flow for deleting products in Salesforce
		deleteProductFromSalesforceFlow = getFlow("deleteProductsFromSalesforceFlow");

		// Flow for deleting products in Sap
		deleteProductFromSapFlow = getFlow("deleteProductsFromSapFlow");

		// Flow for querying the product in Salesforce
		queryProductFromSalesforceFlow = getFlow("queryProductFromSalesforceFlow");

		// Flow for querying the product in Sap
		queryProductFromSapFlow = getFlow("queryProductFromSapFlow");
	}

	private void cleanUpSandboxesByRemovingTestProducts() throws MuleException, Exception {
		final List<String> idList = new ArrayList<String>();
		for (String product : productsCreatedInSalesforce) {
			idList.add(product);
		}
		deleteProductFromSalesforceFlow.process(getTestEvent(idList, MessageExchangePattern.REQUEST_RESPONSE));
		idList.clear();
		for (String product : productsCreatedInSap) {
			idList.add(product);
		}
		deleteProductFromSapFlow.process(getTestEvent(idList, MessageExchangePattern.REQUEST_RESPONSE));
	}

	@Test
	public void testSapToSalesforceUpdate()
			throws MuleException, Exception {

		final Map<String, Object> product = createTestProducts();

		// Execution
		executeWaitAndAssertBatchJob(SAP2SFDC_INBOUND_FLOW_NAME);

		compareProducts(product);
	}

	private Map<String, Object> createTestProducts() throws Exception {
		// Build test products
		final HashMap<String, Object> product = new HashMap<String, Object>();
		String uniqueSuffix = Long.toString(System.currentTimeMillis(), Character.MAX_RADIX).toUpperCase();
		product.put("ProductCode", "S2S_PBS-" + uniqueSuffix);

		final Map<String, Object> sapProduct = (Map<String, Object>) product.clone();
		sapProduct.put("Name", "BATTERY-sap2sfdc-prod-migration-"+ uniqueSuffix);

		// Create products in sand-boxes and keep track of them for posterior cleaning up
		// note that product in target instance doesn't need to exist - other tests could test that too
		productsCreatedInSap.add(createTestProductsInSapSandbox(sapProduct, createProductInSapFlow));
		return sapProduct;
	}

	private void compareProducts(Map<String, Object> product) throws Exception {
		// Assertions
		Map<String, String> retrievedProductFromSalesforce = (Map<String, String>) queryProduct(product, queryProductFromSalesforceFlow);
		
		Object product2 = queryProduct(product, queryProductFromSapFlow);
		Assert.assertFalse("Product from SAP is null", product2 instanceof NullPayload);
		Map<String, String> retrievedProductFromSap = (Map<String, String>) product2;
		
		Assert.assertEquals("Some products are not synchronized between systems.",
				retrievedProductFromSalesforce.get("Name"),
				retrievedProductFromSap.get("Name"));
	}

	private Object queryProduct(Map<String, Object> product, Flow queryProductFlow)
			throws Exception {
		return queryProductFlow.process(getTestEvent(product, MessageExchangePattern.REQUEST_RESPONSE)).getMessage().getPayload();
	}

	private String createTestProductsInSapSandbox(Map<String, Object> product, Flow createProductFlow)
			throws Exception {
		List<Map<String, Object>> salesforceProducts = new ArrayList<Map<String, Object>>();
		salesforceProducts.add(product);

		createProductFlow.process(getTestEvent(salesforceProducts, MessageExchangePattern.REQUEST_RESPONSE)).getMessage().getPayload();
		return (String) product.get("ProductCode"); // ((CreateResult) payloadAfterExecution.get(0)).getCreatedObjects().get(0);
	}

	private void executeWaitAndAssertBatchJob(String flowConstructName) throws Exception {
		// Execute synchronization
		runFlow(flowConstructName);

		// Wait for the batch job execution to finish
		batchTestHelper.awaitJobTermination(TIMEOUT_MILLIS * 1000, 500);
		batchTestHelper.assertJobWasSuccessful();
	}

}
