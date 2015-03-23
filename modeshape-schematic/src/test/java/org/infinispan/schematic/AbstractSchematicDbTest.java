/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.infinispan.schematic;

import javax.transaction.TransactionManager;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.util.concurrent.IsolationLevel;
import org.junit.After;
import org.junit.Before;

public abstract class AbstractSchematicDbTest {

    protected SchematicDb db;
    protected EmbeddedCacheManager cm;
    protected TransactionManager tm;

    @Before
    public void beforeTest() {
        ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();
        configurationBuilder.invocationBatching()
                            .enable()
                            .transaction()
                            .transactionManagerLookup(new DummyTransactionManagerLookup())
                            .transactionMode(TransactionMode.TRANSACTIONAL)
                            .lockingMode(LockingMode.PESSIMISTIC)
                            .locking()
                            .isolationLevel(IsolationLevel.READ_COMMITTED);
        cm = new DefaultCacheManager(configurationBuilder.build());
        // Now create the SchematicDb ...
        db = Schematic.get(cm, "documents");
        tm = db.getCache().getAdvancedCache().getTransactionManager();
    }

    @After
    public void afterTest() {
        try {
            TestUtil.killCacheContainers(cm);
        } finally {
            cm = null;
            db = null;
            try {
                TestUtil.killTransaction(tm);
            } finally {
                tm = null;
            }
        }
    }

    protected SchematicDb db() {
        return db;
    }

    protected TransactionManager tm() {
        return tm;
    }

}
