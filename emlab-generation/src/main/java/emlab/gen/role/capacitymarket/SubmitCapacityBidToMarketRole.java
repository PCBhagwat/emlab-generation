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
import emlab.gen.domain.agent.Regulator;
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
 * 
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
        // logger.warn("***********Submitting Bid Role for Energy Producer ********"
        // + producer.getName());

        // For UK market create bids for under construction plants within 4
        // years of construction and not contracted
        Regulator regulator = reps.regulatorRepository.findRegulatorForZone(producer.getInvestorMarket().getZone());
        for (PowerPlant plant : reps.powerPlantRepository.findPowerPlantsByOwner(producer)) {
            // Create PPDPs only for existing and under construction plants UK
            // Market
            // logger.warn("A Plant LT: " +
            // plant.isHasLongtermCapacityMarketContract());
            long currentLifeTime = 0;
            currentLifeTime = getCurrentTick() - plant.getConstructionStartTime()
                    - plant.getTechnology().getExpectedLeadtime() - plant.getTechnology().getExpectedPermittime();

            if (plant.isTemporaryPlantforCapacityMarketBid() == false
                    && plant.isHasLongtermCapacityMarketContract() == false
                    && currentLifeTime > (regulator.getCapacityMarketPermittedTimeForConstruction() * (-1))
                    && currentLifeTime <= 0) {
                double capacity = plant.getTechnology().getCapacity()
                        * plant.getTechnology().getPeakSegmentDependentAvailability();
                CapacityMarket market = reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
                        .getZone());

                CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
                plan.specifyAndPersist(plant, producer, market, getCurrentTick(), plant.getActualFixedOperatingCost()
                        / (plant.getTechnology().getCapacity() * plant.getTechnology()
                                .getPeakSegmentDependentAvailability()), capacity, Bid.SUBMITTED);

            }

            if (plant.isTemporaryPlantforCapacityMarketBid() == false
                    && plant.isHasLongtermCapacityMarketContract() == false && currentLifeTime > 0) {

                // logger.warn("Bid calculation for PowerPlant " +
                // plant.isTemporaryPlantforCapacityMarketBid());
                // get market for the plant by zone
                CapacityMarket market = reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
                        .getZone());
                if (market != null) {
                    ElectricitySpotMarket eMarket = reps.marketRepository.findElectricitySpotMarketForZone(plant
                            .getLocation().getZone());

                    double mc = 0d;
                    double bidPrice = 0d;
                    double expectedElectricityRevenues = 0;
                    double netRevenues = 0;

                    if (getCurrentTick() == 0) {
                        mc = 0;
                        expectedElectricityRevenues = 0d;

                    } else {
                        mc = calculateMarginalCostExclCO2MarketCost(plant, getCurrentTick());
                    }

                    double fixedOnMCost = plant.getActualFixedOperatingCost()
                            / (plant.getTechnology().getCapacity() * plant.getTechnology()
                                    .getPeakSegmentDependentAvailability());

                    // logger.warn("Bid calculation for PowerPlant " +
                    // plant.getName());
                    // get market for the plant by zone

                    // logger.warn("CapacityMarket is  " + market.getName());

                    for (SegmentLoad segmentLoad : eMarket.getLoadDurationCurve()) {
                        double segmentClearingPoint = 0;

                        if (getCurrentTick() > 0) {
                            segmentClearingPoint = reps.segmentClearingPointRepository
                                    .findSegmentClearingPointForMarketSegmentAndTime(getCurrentTick() - 1,
                                            segmentLoad.getSegment(), eMarket, false).getPrice();
                        } else {
                            if (getCurrentTick() == 0) {
                                segmentClearingPoint = 0;
                            }

                        }
                        //
                        // SimpleRegression sr = new SimpleRegression();
                        // long time = 0l;
                        // for (time = getCurrentTick() - 1; time >
                        // getCurrentTick()
                        // -
                        // plant.getOwner().getNumberOfYearsBacklookingForForecasting()
                        // && time > 0; time = time - 1) {
                        //
                        // double eprice = 0;
                        //
                        // eprice = reps.segmentClearingPointRepository
                        // .findSegmentClearingPointForMarketSegmentAndTime(time,
                        // segmentLoad.getSegment(),
                        // eMarket, false).getPrice();
                        // sr.addData(time, eprice);
                        //
                        // }
                        // if (getCurrentTick() > 2) {
                        // double tempSCP = (sr.predict(getCurrentTick()));
                        // if (tempSCP < 0) {
                        // segmentClearingPoint = 0;
                        // } else {
                        // segmentClearingPoint =
                        // (sr.predict(getCurrentTick()));
                        // }
                        //
                        // } else {
                        // if (getCurrentTick() <= 2 && getCurrentTick() > 0) {
                        // segmentClearingPoint =
                        // reps.segmentClearingPointRepository
                        // .findSegmentClearingPointForMarketSegmentAndTime(getCurrentTick()
                        // - 1,
                        // segmentLoad.getSegment(), eMarket, false).getPrice();
                        // }
                        // if (getCurrentTick() == 0) {
                        // segmentClearingPoint = 0;
                        // }
                        // }

                        double plantLoadFactor = ((plant.getTechnology().getPeakSegmentDependentAvailability()) + (((plant
                                .getTechnology().getBaseSegmentDependentAvailability() - plant.getTechnology()
                                .getPeakSegmentDependentAvailability()) / ((double) (reps.segmentRepository
                                .findBaseSegmentforMarket(eMarket).getSegmentID() - 1))) * (segmentLoad.getSegment()
                                .getSegmentID() - 1)));

                        if (segmentClearingPoint >= mc) {
                            expectedElectricityRevenues = expectedElectricityRevenues
                                    + ((segmentClearingPoint - mc) * plant.getActualNominalCapacity() * plantLoadFactor * segmentLoad
                                            .getSegment().getLengthInHours());

                        }

                    }

                    netRevenues = (expectedElectricityRevenues / (plant.getActualNominalCapacity() * plant
                            .getTechnology().getPeakSegmentDependentAvailability())) - fixedOnMCost;
                    if (getCurrentTick() > 0) {
                        if (netRevenues >= 0) {
                            bidPrice = 0d;
                            // } else if (mcCapacity <= fixedOnMCost) {
                        } else {
                            bidPrice = netRevenues * (-1);
                        }
                    } else {
                        if (getCurrentTick() == 0) {
                            bidPrice = 0;
                        }
                    }
                    double capacity = plant.getActualNominalCapacity()
                            * plant.getTechnology().getPeakSegmentDependentAvailability();

                    CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
                    plan.specifyAndPersist(plant, producer, market, getCurrentTick(), bidPrice, capacity, Bid.SUBMITTED);

                    // logger.warn("CDP for powerplant " +
                    // plan.getPlant().getName()
                    // + "rev " + netRevenues);
                    // logger.warn("CDP price is " + plan.getPrice());
                    // logger.warn("CDP amount is " + plan.getAmount());

                }
            }
            // if (plant.isTemporaryPlantforCapacityMarketBid() == true) {
            // // logger.warn("enters loop " +
            // // plant.getCapacityMarketBidPrice());
            // double capacity = plant.getTechnology().getCapacity()
            // * plant.getTechnology().getPeakSegmentDependentAvailability();
            // CapacityMarket market =
            // reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
            // .getZone());
            //
            // CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
            // plan.specifyAndPersist(plant, producer, market, getCurrentTick(),
            // (plant.getCapacityMarketBidPrice() +
            // (plant.getActualFixedOperatingCost() / capacity)),
            // capacity, Bid.SUBMITTED);
            //
            // }
        }
    }
}