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

package org.modeshape.jboss.subsystem;

import org.jboss.as.controller.AbstractRemoveStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;

/**
 * {@link org.jboss.as.controller.AbstractRemoveStepHandler} which is triggered each time an <webapp/> element is removed from the ModeShape subsystem.
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
class RemoveWebApp extends AbstractRemoveStepHandler {

    static final RemoveWebApp INSTANCE = new RemoveWebApp();

    @Override
    protected void performRemove( OperationContext context,
                                  ModelNode operation,
                                  ModelNode model ) throws OperationFailedException {
        if (!requiresRuntime(context)) {
            //we need to skip the execution of this handler if it does not require a "runtime mode", because its corresponding
            //AddWebApp handler only deploys the webapp in runtime mode, so there's nothing to clean up.
            //Runtime mode is something that seems to be required only for "normal" servers, as opposed to domain controllers,
            //admin-mode servers and the likes. A standalone or a host in a group of servers will be considered "normal".
            return;
        }

        if (!model.isDefined()) {
            //the model hasn't been defined, which means the Add Step did not succeed
            return;
        }
        AddressContext addressContext = AddressContext.forOperation(operation);
        String webappName = addressContext.lastPathElementValue();

        PathAddress deploymentAddress = PathAddress.pathAddress(PathElement.pathElement(ModelDescriptionConstants.DEPLOYMENT, webappName));
        ModelNode deploymentOp = Util.createOperation(ModelDescriptionConstants.DEPLOYMENT, deploymentAddress);

        ImmutableManagementResourceRegistration rootResourceRegistration = context.getRootResourceRegistration();
        OperationStepHandler removeDeploymentHandler = rootResourceRegistration.getOperationHandler(deploymentAddress, ModelDescriptionConstants.REMOVE);
        context.addStep(deploymentOp, removeDeploymentHandler, OperationContext.Stage.MODEL);

        super.performRemove(context, operation, model);
    }
}
