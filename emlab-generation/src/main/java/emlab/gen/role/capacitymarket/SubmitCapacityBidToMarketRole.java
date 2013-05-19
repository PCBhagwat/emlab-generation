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

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import agentspring.role.Role;
import agentspring.role.RoleComponent;
import emlab.gen.domain.agent.EnergyProducer;
import emlab.gen.domain.market.Bid;
import emlab.gen.domain.market.capacity.CapacityDispatchPlan;
import emlab.gen.domain.market.capacity.CapacityMarket;
import emlab.gen.domain.market.electricity.ElectricitySpotMarket;
import emlab.gen.domain.market.electricity.SegmentLoad;
import emlab.gen.domain.technology.PowerPlant;
import emlab.gen.repository.Reps;
import emlab.gen.role.AbstractEnergyProducerRole;

//import org.springframework.data.neo4j.annotation.NodeEntity;

/**
 * @author Kaveri
 * 
 */

@RoleComponent
public class SubmitCapacityBidToMarketRole extends AbstractEnergyProducerRole<EnergyProducer> implements
        Role<EnergyProducer> {

    Logger logger = Logger.getLogger(SubmitCapacityBidToMarketRole.class);

    @Autowired
    Reps reps;

    @Override
    @Transactional
    public void act(EnergyProducer producer) {
        logger.warn("Itsme");

        for (PowerPlant plant : reps.powerPlantRepository.findOperationalPowerPlantsByOwner(producer, getCurrentTick())) {

            logger.warn("Power Plant is " + plant.getName());

            // get market for the plant by zone
            CapacityMarket market = reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
                    .getZone());

            // logger.warn("Capacity Market is " + market.getName());

            ElectricitySpotMarket eMarket = reps.marketRepository.findElectricitySpotMarketForZone(plant.getLocation()
                    .getZone());

            logger.warn("Electricity Market is " + eMarket.getName());

            // compute bid price as (fixedOMCost - elecricityMarketRevenue), if
            // the difference is positive. Else if negative, bid at zero.
            double bidPrice = 0d;

            // get FixedOMCost
            double fixedOnMCost = plant.getTechnology().getFixedOperatingCost(getCurrentTick());

            // compute revenue from the energy market, using previous tick's
            // electricity spot market prices
            long numberOfSegments = reps.segmentRepository.count();
            double electricityMarketRevenue = 0d;
            // double runningHours = 0d;

            // double mc = calculateMarginalCostExclCO2MarketCost(plant);
            double expectedElectricityPrice = 0;
            double mc = 0;
            for (SegmentLoad segmentLoad : eMarket.getLoadDurationCurve()) {

                if (getCurrentTick() == 0) {
                    mc = 0;
                    expectedElectricityPrice = 0;

                } else {
                    expectedElectricityPrice = reps.segmentClearingPointRepository
                            .findOneSegmentClearingPointForMarketSegmentAndTime(getCurrentTick() - 1,
                                    segmentLoad.getSegment(), eMarket).getPrice();
                }

                double hours = segmentLoad.getSegment().getLengthInHours();
                if (mc <= expectedElectricityPrice) {
                    // runningHours += hours;
                    electricityMarketRevenue += (expectedElectricityPrice - mc) * hours
                            * plant.getAvailableCapacity(getCurrentTick(), segmentLoad.getSegment(), numberOfSegments);
                }
            }

            double mcCapacity = fixedOnMCost - electricityMarketRevenue;

            if (mcCapacity < 0) {
                bidPrice = 0d;
            } else if (mcCapacity <= fixedOnMCost) {
                bidPrice = mcCapacity;
            }

            // logger.info("Submitting offers for {} with technology {}",
            // plant.getName(), plant.getTechnology().getName());

            double capacity = plant.getAvailableCapacity(getCurrentTick(), null, numberOfSegments);
            // logger.info("I bid capacity: {} and price: {} into the capacity market",
            // capacity, bidPrice);

            CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
            // plan.specifyNotPersist(plant, producer, market, segment,
            // time, price, bidWithoutCO2, spotMarketCapacity,
            // longTermContractCapacity, status);
            plan.specifyAndPersist(plant, producer, market, getCurrentTick(), bidPrice, capacity, Bid.SUBMITTED);

            // logger.info("Submitted {} for iteration {} to capacity market",
            // plan);
            System.out.print(" Submitted bid at price" + bidPrice);
            System.out.print(" And capacity" + capacity);

        }
    }

}