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
import emlab.gen.domain.market.electricity.PowerPlantDispatchPlan;
import emlab.gen.domain.market.electricity.Segment;
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

            long currentLifeTime = getCurrentTick() - plant.getConstructionStartTime()
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
                plan.specifyAndPersist(plant, producer, market, getCurrentTick(), 0, capacity, Bid.SUBMITTED);

            }

            if (plant.isTemporaryPlantforCapacityMarketBid() == false
                    && plant.isHasLongtermCapacityMarketContract() == false && currentLifeTime > 0) {

                // logger.warn("Bid calculation for PowerPlant " +
                // plant.isTemporaryPlantforCapacityMarketBid());
                // get market for the plant by zone
                CapacityMarket market = reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
                        .getZone());
                if (market != null) {
                    // logger.warn("CapacityMarket is  " + market.getName());

                    ElectricitySpotMarket eMarket = reps.marketRepository.findElectricitySpotMarketForZone(plant
                            .getLocation().getZone());

                    // compute bid price as (fixedOMCost -
                    // elecricityMarketRevenue),
                    // if
                    // the difference is positive. Else if negative, bid at
                    // zero.
                    double bidPrice = 0d;

                    // get FixedOMCost
                    double fixedOnMCost = plant.getTechnology().getFixedOperatingCost(getCurrentTick());
                    // logger.warn("FIxed OM cost is  " + fixedOnMCost);

                    // logger.warn("fixed operation and maintenance cost is " +
                    // fixedOnMCost);

                    double expectedElectricityPrice = 0;
                    double electricityMarketRevenue = 0d;
                    long numberOfSegments = reps.segmentRepository.count();
                    double mc = 0d;
                    if (getCurrentTick() == 0) {
                        mc = 0;
                        electricityMarketRevenue = 0d;

                    } else {

                        // ********** to check if plant was in the merit order
                        // or
                        // not in the previous tick, hence tickTemp******
                        long tickTemp = (getCurrentTick() - 1);
                        // logger.warn("current tick - 1 is " + tickTemp);

                        PowerPlantDispatchPlan ppdpTest = reps.powerPlantDispatchPlanRepository
                                .findOnePowerPlantDispatchPlanForPeakSegmentGivenPowerPlantAndTime(plant, tickTemp,
                                        false);

                        if (ppdpTest == null) {

                        } else {
                            // compute revenue from the energy market, using
                            // previous
                            // tick's
                            // electricity spot market prices
                            double capacityAccepted = 0d;
                            mc = calculateMarginalCostExclCO2MarketCost(plant, getCurrentTick());

                            double sumEMR = 0d;

                            for (SegmentLoad segmentLoad : eMarket.getLoadDurationCurve()) {

                                PowerPlantDispatchPlan ppdp = reps.powerPlantDispatchPlanRepository
                                        .findOnePowerPlantDispatchPlanForPowerPlantForSegmentForTime(plant,
                                                segmentLoad.getSegment(), tickTemp);

                                if (ppdp.getStatus() < 0) {
                                    electricityMarketRevenue = 0d;
                                } else if (ppdp.getStatus() >= 2) {
                                    capacityAccepted = ppdp.getAcceptedAmount();

                                    expectedElectricityPrice = reps.segmentClearingPointRepository
                                            .findOneSegmentClearingPointForMarketSegmentAndTime(getCurrentTick() - 1,
                                                    segmentLoad.getSegment(), eMarket).getPrice();

                                    double hours = segmentLoad.getSegment().getLengthInHours();
                                    // logger.warn("Number of hours per segment is"logger.warn("EL Market revenue is "
                                    // + electricityMarketRevenue);
                                    // +
                                    // hours);

                                    if (mc <= expectedElectricityPrice) {
                                        sumEMR = sumEMR + (expectedElectricityPrice - mc) * hours
                                                * ppdp.getAcceptedAmount();
                                        // logger.warn("EL Market revenue for this segment is "
                                        // + sumEMR);
                                    }

                                }

                            }

                            electricityMarketRevenue = sumEMR;
                        }
                    }

                    double electricityMarketRevenuePerMW = electricityMarketRevenue
                            / plant.getTechnology().getCapacity();
                    // logger.warn("FINAL EL Market revenue is " +
                    // electricityMarketRevenue);
                    // logger.warn("EL Market revenue per MW is " +
                    // electricityMarketRevenuePerMW);

                    double mcCapacity = fixedOnMCost - electricityMarketRevenuePerMW;
                    // logger.warn("Fixed Cost - ESM Rev = " + mcCapacity);

                    if (mcCapacity < 0) {
                        bidPrice = 0d;
                        // } else if (mcCapacity <= fixedOnMCost) {
                    } else {
                        bidPrice = mcCapacity;
                    }

                    Segment peakSegment = reps.segmentRepository.findPeakSegmentforMarket(eMarket);
                    double capacity = plant.getTechnology().getCapacity()
                            * plant.getTechnology().getPeakSegmentDependentAvailability();

                    CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
                    plan.specifyAndPersist(plant, producer, market, getCurrentTick(), bidPrice, capacity, Bid.SUBMITTED);

                    // logger.warn("CDP for powerplant " +
                    // plan.getPlant().getName());
                    // logger.warn("CDP price is " + plan.getPrice());
                    // logger.warn("CDP amount is " + plan.getAmount());

                }
            }
            if (plant.isTemporaryPlantforCapacityMarketBid() == true) {
                // logger.warn("enters loop " +
                // plant.isTemporaryPlantforCapacityMarketBid());
                double capacity = plant.getTechnology().getCapacity()
                        * plant.getTechnology().getPeakSegmentDependentAvailability();
                CapacityMarket market = reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
                        .getZone());

                CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
                plan.specifyAndPersist(plant, producer, market, getCurrentTick(), plant.getCapacityMarketBidPrice(),
                        capacity, Bid.SUBMITTED);

            }
        }
    }
}