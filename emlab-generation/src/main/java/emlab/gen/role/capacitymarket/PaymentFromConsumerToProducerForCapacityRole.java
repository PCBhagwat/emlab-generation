/*******************************************************************************
 * Copyright 2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package emlab.gen.role.capacitymarket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import agentspring.role.Role;
import agentspring.role.RoleComponent;
import emlab.gen.domain.contract.CashFlow;
import emlab.gen.domain.market.ClearingPoint;
import emlab.gen.domain.market.capacity.CapacityDispatchPlan;
import emlab.gen.domain.market.capacity.CapacityMarket;
import emlab.gen.repository.Reps;
import emlab.gen.role.market.AbstractMarketRole;

//import org.springframework.data.neo4j.annotation.NodeEntity;

/**
 * @author Kaveri
 * 
 */
@RoleComponent
public class PaymentFromConsumerToProducerForCapacityRole extends AbstractMarketRole<CapacityMarket> implements
Role<CapacityMarket> {

    @Autowired
    Reps reps;

    // CashFlow cash = new CashFlow();

    @Override
    @Transactional
    public void act(CapacityMarket capacityMarket) {

        for (CapacityDispatchPlan plan : reps.capacityMarketRepository.findAllAcceptedCapacityDispatchPlansForTime(
                capacityMarket, getCurrentTick())) {

            ClearingPoint capacityClearingPoint = reps.capacityMarketRepository
                    .findOneClearingPointForTimeAndCapacityMarket(getCurrentTick(), capacityMarket);

            // double price = capacityClearingPoint.getPrice();
            logger.warn("consumer is " + capacityMarket.getConsumer().getName());
            logger.warn("the bidder is " + plan.getBidder().getName());
            logger.warn("Amount to be transferred is " + plan.getAcceptedAmount());
            logger.warn("Clearing Point price is " + capacityClearingPoint.getPrice());
            logger.warn("plant related to plant" + plan.getPlant().getName());
            reps.nonTransactionalCreateRepository.createCashFlow(capacityMarket.getConsumer(), plan.getBidder(),
                    plan.getAcceptedAmount() * capacityClearingPoint.getPrice(), CashFlow.SIMPLE_CAPACITY_MARKET,
                    getCurrentTick(), plan.getPlant());
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see emlab.gen.role.market.AbstractMarketRole#getReps()
     */
    @Override
    public Reps getReps() {

        return reps;

    }

}