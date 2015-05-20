/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.kiji.mapreduce.produce.KijiProducer;
import org.kiji.mapreduce.produce.ProducerContext;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.layout.KijiTableLayouts;
import org.kiji.schema.util.InstanceBuilder;
import org.kiji.scoring.KijiFreshnessManager.FreshnessValidationException;
import org.kiji.scoring.KijiFreshnessManager.MultiFreshnessValidationException;
import org.kiji.scoring.KijiFreshnessManager.ValidationFailure;
import org.kiji.scoring.avro.KijiFreshnessPolicyRecord;
import org.kiji.scoring.lib.AlwaysFreshen;
import org.kiji.scoring.lib.NeverFreshen;
import org.kiji.scoring.lib.ShelfLife;

public class TestKijiFreshnessManager {
  /** A Kiji instance for testing. */
  private Kiji mKiji;
  private KijiFreshnessManager mFreshManager;

  private static final class TestProducer extends KijiProducer {
    public KijiDataRequest getDataRequest() {
      return KijiDataRequest.create("info", "name");
    }
    public String getOutputColumn() {
      return "info:name";
    }
    public void produce(
        final KijiRowData kijiRowData, final ProducerContext producerContext) throws IOException {
      producerContext.put("new-val");
    }
  }

  private static final class TestFamilyProducer extends KijiProducer {
    public KijiDataRequest getDataRequest() {
      return KijiDataRequest.create("info", "name");
    }
    public String getOutputColumn() {
      return "networks";
    }
    public void produce(
        final KijiRowData kijiRowData, final ProducerContext producerContext) throws IOException {
      producerContext.put("qualifier", "new-val");
    }
  }

  @Before
  public void setup() throws Exception {
    // Get the test table layouts.
    final KijiTableLayout layout = KijiTableLayout.newLayout(
        KijiTableLayouts.getLayout(KijiTableLayouts.USER_TABLE));

    // Populate the environment.
    mKiji = new InstanceBuilder()
        .withTable("user", layout)
          .withRow("foo")
            .withFamily("info")
              .withQualifier("name").withValue(5L, "foo-val")
              .withQualifier("email").withValue(5L, "foo@bar.org")
          .withRow("bar")
             .withFamily("info")
                .withQualifier("name").withValue(5L, "bar-val")
                .withQualifier("email").withValue(5L, "bar@foo.org")
        .build();

    // Fill local variables.
    mFreshManager = KijiFreshnessManager.create(mKiji);
  }

  @After
  public void cleanup() throws Exception {
    mFreshManager.close();
    mKiji.release();
  }

  /** Tests that we can store a policy and retrieve it. */
  @Test
  public void testStorePolicy() throws Exception {
    ShelfLife policy = new ShelfLife(100);
    mFreshManager.storePolicy("user", "info:name", TestProducer.class, policy);
    KijiFreshnessPolicyRecord mPolicyRecord = mFreshManager.retrievePolicy("user", "info:name");
    assertEquals(mPolicyRecord.getProducerClass(), TestProducer.class.getName());
    assertEquals(mPolicyRecord.getFreshnessPolicyClass(), ShelfLife.class.getName());
    ShelfLife policyLoaded = new ShelfLife();
    policyLoaded.deserialize(mPolicyRecord.getFreshnessPolicyState());
    assertEquals(policy.getShelfLifeInMillis(), policyLoaded.getShelfLifeInMillis());
  }

  /** Test that we can store a policy and state using unchecked strings. */
  @Test
  public void testStorePolicyWithStrings() throws Exception {
    String policyClassString = "org.kiji.imaginary.Policy"; // Doesn't exist.
    String policyState = "SomeState";
    String producerClassString = "org.kiji.imaginary.Producer";
    mFreshManager.storePolicyWithStrings(
        "user", "info:name", producerClassString, policyClassString, policyState);
    KijiFreshnessPolicyRecord mPolicyRecord = mFreshManager.retrievePolicy("user", "info:name");
    assertEquals(mPolicyRecord.getProducerClass(), producerClassString);
    assertEquals(mPolicyRecord.getFreshnessPolicyClass(), policyClassString);
    assertEquals(mPolicyRecord.getFreshnessPolicyState(), policyState);
  }

  /** Tests that we can store multiple policies and retrieve them. */
  @Test
  public void testRetrievePolicies() throws Exception {
    ShelfLife policy = new ShelfLife(100);
    mFreshManager.storePolicy("user", "info:name", TestProducer.class, policy);
    mFreshManager.storePolicy("user", "info:email", TestProducer.class, policy);
    mFreshManager.storePolicy("user", "networks", TestFamilyProducer.class, policy);
    Map<KijiColumnName, KijiFreshnessPolicyRecord> policies =
        mFreshManager.retrievePolicies("user");
    assertEquals(3, policies.size());
    assertTrue(policies.containsKey(new KijiColumnName("info", "name")));
    assertTrue(policies.containsKey(new KijiColumnName("info", "email")));
    assertTrue(policies.containsKey(new KijiColumnName("networks")));
  }

  /** Tests that retrieving a policy that doesn't exist returns null. */
  @Test
  public void testEmptyRetrieve() throws Exception {
    assertNull(mFreshManager.retrievePolicy("user", "info:name"));
  }

  /** Tests that we can remove policies correctly. */
  @Test
  public void testPolicyRemoval() throws Exception {
    ShelfLife policy = new ShelfLife(100);
    mFreshManager.storePolicy("user", "info:name", TestProducer.class, policy);
    KijiFreshnessPolicyRecord mPolicyRecord = mFreshManager.retrievePolicy("user", "info:name");
    assertNotNull(mPolicyRecord);
    mFreshManager.removePolicy("user", "info:name");
    mPolicyRecord = mFreshManager.retrievePolicy("user", "info:name");
    assertNull(mPolicyRecord);
  }

  @Test
  public void testInvalidColumnAttachment() throws IOException {
    final ShelfLife policy = new ShelfLife(100);

    try {
      mFreshManager.storePolicy("user", "info:invalid", TestProducer.class, policy);
      fail();
    } catch (FreshnessValidationException fve) {
      assertEquals("There were validation failures.\nNO_QUALIFIED_COLUMN_IN_TABLE: Table: user does"
          + " not contain specified column: info:invalid", fve.getMessage());
    }

    try {
      mFreshManager.storePolicy("user", "info", TestFamilyProducer.class, policy);
      fail();
    } catch (FreshnessValidationException fve) {
      assertEquals("There were validation failures.\nGROUP_TYPE_FAMILY_ATTACHMENT: Specified "
          + "family: info is not a valid Map Type family in the table: user", fve.getMessage());
    }

    mFreshManager.storePolicy("user", "networks", TestFamilyProducer.class, policy);
    try {
      mFreshManager.storePolicy("user", "networks:qualifier", TestProducer.class, policy);
    } catch (FreshnessValidationException fve) {
      assertEquals("There were validation failures.\nFRESHENER_ALREADY_ATTACHED: There is already a"
          + " freshness policy attached to family: networks Freshness "
          + "policies may not be attached to a map type family and fully qualified columns within "
          + "that family.", fve.getMessage());
    }


    mFreshManager.removePolicy("user", "networks");
    mFreshManager.storePolicy("user", "networks:qualifier", TestProducer.class, policy);
    try {
      mFreshManager.storePolicy("user", "networks", TestFamilyProducer.class, policy);
    } catch (FreshnessValidationException fve) {
      assertEquals("There were validation failures.\nFRESHENER_ALREADY_ATTACHED: There is already a"
          + " freshness policy attached to a fully qualified column in "
          + "family: networks Freshness policies may not be attached to a map type family and fully"
          + " qualified columns within that family. To view a list of attached freshness policies "
          + "check log files for KijiFreshnessManager.", fve.getMessage());
    }
    // This should pass.
    mFreshManager.storePolicy("user", "info:name", TestProducer.class, policy);
  }

  @Test
  public void testJavaIdentifiers() throws IOException {
    try {
      mFreshManager.storePolicyWithStrings(
          "user", "networks", "kiji..producer", "kiji.policy.policy", "");
      fail();
    } catch (FreshnessValidationException fve) {
      assertEquals("There were validation failures.\nBAD_PRODUCER_NAME: Producer class name: "
          + "kiji..producer is not a valid Java class identifier.", fve.getMessage());
    }

    try {
      mFreshManager.storePolicyWithStrings("user", "networks", "kiji.a.producer", "kiji.", "");
    } catch (FreshnessValidationException fve) {
      assertEquals("There were validation failures.\nBAD_POLICY_NAME: Policy class name: kiji. is "
          + "not a valid Java class identifier.", fve.getMessage());
    }

    try {
      mFreshManager.storePolicyWithStrings("user", "networks", "kiji.a.producer", ".kiji", "");
    } catch (FreshnessValidationException fve) {
      assertEquals("There were validation failures.\nBAD_POLICY_NAME: Policy class name: .kiji is "
          + "not a valid Java class identifier.", fve.getMessage());
    }
  }

  @Test
  public void testRemovePolicies() throws IOException {
    mFreshManager.storePolicy("user", "info:name", TestProducer.class, new AlwaysFreshen());
    mFreshManager.storePolicy("user", "info:email", TestProducer.class, new AlwaysFreshen());
    assertEquals(
        Sets.newHashSet(new KijiColumnName("info:name"), new KijiColumnName("info:email")),
        mFreshManager.removePolicies("user"));

    assertEquals(new HashSet<KijiColumnName>(), mFreshManager.removePolicies("user"));
  }

  @Test
  public void testStorePolicies() throws IOException {
    mFreshManager.storePolicy("user", "info:name", TestProducer.class, new AlwaysFreshen());
    mFreshManager.storePolicy("user", "info:email", TestProducer.class, new AlwaysFreshen());
    final Map<KijiColumnName, KijiFreshnessPolicyRecord> records =
        mFreshManager.retrievePolicies("user");
    assertEquals(2, records.size());

    final Map<KijiColumnName, KijiFreshnessPolicyRecord> modifiedRecords = Maps.newHashMap(records);
    for (Map.Entry<KijiColumnName, KijiFreshnessPolicyRecord> entry : modifiedRecords.entrySet()) {
      entry.getValue().setFreshnessPolicyClass("org.kiji.scoring.lib.NeverFreshen");
    }

    mFreshManager.storePolicies("user", modifiedRecords, true);
    assertEquals("org.kiji.scoring.lib.NeverFreshen",
        mFreshManager.retrievePolicy("user", "info:name").getFreshnessPolicyClass());
    assertEquals("org.kiji.scoring.lib.NeverFreshen",
        mFreshManager.retrievePolicy("user", "info:email").getFreshnessPolicyClass());

    try {
      mFreshManager.storePolicies("user", records, false);
      fail("storePolicies() should have thrown MultiFreshnessValidationException because of already"
          + " attached fresheners.");
    } catch (MultiFreshnessValidationException mfve) {
      final Map<KijiColumnName, Map<ValidationFailure, Exception>> failures = mfve.getExceptions();
      assertEquals(2, failures.size());
      assertTrue(failures.containsKey(new KijiColumnName("info:name")));
      assertTrue(failures.containsKey(new KijiColumnName("info:email")));
      assertTrue(failures.get(new KijiColumnName("info:name"))
          .containsKey(ValidationFailure.FRESHENER_ALREADY_ATTACHED));
      assertTrue(failures.get(new KijiColumnName("info:email"))
          .containsKey(ValidationFailure.FRESHENER_ALREADY_ATTACHED));
    }
  }

  public void testOptionalFields() throws IOException {
    final Map<String, String> params = Maps.newHashMap();
    params.put("test-key", "test-value");
    final KijiFreshnessPolicy policy = new NeverFreshen();

    final KijiFreshnessPolicyRecord parametersRecord = KijiFreshnessPolicyRecord.newBuilder()
        .setRecordVersion(KijiFreshnessManager.CUR_FRESHNESS_RECORD_VER.toCanonicalString())
        .setProducerClass(TestProducer.class.getName())
        .setFreshnessPolicyClass(NeverFreshen.class.getName())
        .setFreshnessPolicyState("")
        .setParameters(params)
        .build();
    assertEquals(false, parametersRecord.getReinitializeProducer());

    final KijiFreshnessPolicyRecord reinitializeRecord = KijiFreshnessPolicyRecord.newBuilder()
        .setRecordVersion(KijiFreshnessManager.CUR_FRESHNESS_RECORD_VER.toCanonicalString())
        .setProducerClass(TestProducer.class.getName())
        .setFreshnessPolicyClass(NeverFreshen.class.getName())
        .setFreshnessPolicyState("")
        .setReinitializeProducer(true)
        .build();
    assertTrue(reinitializeRecord.getParameters().isEmpty());

    final KijiFreshnessPolicyRecord combinedRecord = KijiFreshnessPolicyRecord.newBuilder()
        .setRecordVersion(KijiFreshnessManager.CUR_FRESHNESS_RECORD_VER.toCanonicalString())
        .setProducerClass(TestProducer.class.getName())
        .setFreshnessPolicyClass(NeverFreshen.class.getName())
        .setFreshnessPolicyState("")
        .setParameters(params)
        .setReinitializeProducer(true)
        .build();

    mFreshManager.storePolicy("user", "info:name", TestProducer.class, policy, params, false);
    assertEquals(parametersRecord, mFreshManager.retrievePolicy("user", "info:name"));
    mFreshManager.removePolicy("user", "info:name");

    mFreshManager.storePolicy(
        "user",
        "info:name",
        TestProducer.class,
        policy,
        Collections.<String, String>emptyMap(),
        true);
    assertEquals(reinitializeRecord, mFreshManager.retrievePolicy("user", "info:name"));
    mFreshManager.removePolicy("user", "info:name");

    mFreshManager.storePolicy("user", "info:name", TestProducer.class, policy, params, true);
    assertEquals(combinedRecord, mFreshManager.retrievePolicy("user", "info:name"));
  }
}
