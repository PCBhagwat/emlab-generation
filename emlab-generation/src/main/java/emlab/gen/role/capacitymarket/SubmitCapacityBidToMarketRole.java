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
import emlab.gen.domain.contract.CashFlow;
import emlab.gen.domain.market.Bid;
import emlab.gen.domain.market.capacity.CapacityDispatchPlan;
import emlab.gen.domain.market.capacity.CapacityMarket;
import emlab.gen.domain.market.electricity.ElectricitySpotMarket;
import emlab.gen.domain.market.electricity.PowerPlantDispatchPlan;
import emlab.gen.domain.market.electricity.Segment;
import emlab.gen.domain.technology.PowerPlant;
import emlab.gen.repository.Reps;
import emlab.gen.role.AbstractEnergyProducerRole;

//import org.springframework.data.neo4j.annotation.NodeEntity;

/**
 * @author
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
        Regulator regulator = reps.regulatorRepository.findRegulatorForZone(producer.getInvestorMarket().getZone());

        for (PowerPlant plant : reps.powerPlantRepository.findPowerPlantsByOwner(producer)) {
            long constructionTimeRemaining = getCurrentTick() - plant.getConstructionStartTime()
                    - plant.getTechnology().getExpectedLeadtime() - plant.getTechnology().getExpectedPermittime();
            if (plant.isHasLongtermCapacityContracts() != true && constructionTimeRemaining < 0
                    && constructionTimeRemaining > (regulator.getCapacityMarketTimePermittedForConstruction() * (-1))) {
                CapacityMarket market = reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
                        .getZone());
                if (market != null) {
                    double bidPrice = plant.getActualFixedOperatingCost()
                            / (plant.getActualNominalCapacity() * plant.getTechnology()
                                    .getPeakSegmentDependentAvailability());
                    double bidCapacity = (plant.getActualNominalCapacity() * plant.getTechnology()
                            .getPeakSegmentDependentAvailability());

                    CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
                    plan.specifyAndPersist(plant, producer, market, getCurrentTick(), bidPrice, bidCapacity,
                            Bid.SUBMITTED);
                    // logger.warn("1 New bid " + plan.getPrice());
                }

            }

        }

        for (PowerPlant plant : reps.powerPlantRepository.findOperationalPowerPlantsByOwner(producer, getCurrentTick())) {
            if (plant.isHasLongtermCapacityContracts() != true) {

                CapacityMarket market = reps.capacityMarketRepository.findCapacityMarketForZone(plant.getLocation()
                        .getZone());
                if (market != null) {
                    ElectricitySpotMarket eMarket = reps.marketRepository.findElectricitySpotMarketForZone(plant
                            .getLocation().getZone());

                    double mc = 0d;
                    double bidPrice = 0d;
                    double expectedElectricityRevenues = 0;
                    double netRevenues = 0;
                    long constructionTime = plant.getActualLeadtime() + plant.getActualPermittime()
                            + plant.getConstructionStartTime();

                    if (constructionTime == getCurrentTick()) {

                        bidPrice = (plant.getActualFixedOperatingCost() / (plant.getActualNominalCapacity() * plant
                                .getTechnology().getPeakSegmentDependentAvailability()));
                        double bidCapacity = ((plant.getActualNominalCapacity() * plant.getTechnology()
                                .getPeakSegmentDependentAvailability()));

                        CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
                        plan.specifyAndPersist(plant, producer, market, getCurrentTick(), bidPrice, bidCapacity,
                                Bid.SUBMITTED);
                    }

                    if (constructionTime < getCurrentTick()) {

                        if (getCurrentTick() == 0) {
                            mc = 0;
                            expectedElectricityRevenues = 0d;

                        } else {
                            mc = calculateMarginalCostExclCO2MarketCost(plant, getCurrentTick());
                        }

                        double fixedOnMCost = plant.getActualFixedOperatingCost();

                        for (CashFlow cf : reps.cashFlowRepository.findAllCashFlowsForPowerPlantForTime(plant,
                                (getCurrentTick() - 1))) {
                            if (cf.getType() == CashFlow.ELECTRICITY_SPOT) {
                                expectedElectricityRevenues = expectedElectricityRevenues + cf.getMoney();
                            }
                        }

                        double plantMarginalCost = 0;
                        for (Segment currentSegment : reps.segmentRepository.findAll()) {

                            PowerPlantDispatchPlan ppdp = new PowerPlantDispatchPlan();
                            ppdp = reps.powerPlantDispatchPlanRepository
                                    .findOnePowerPlantDispatchPlanForPowerPlantForSegmentForTime(plant, currentSegment,
                                            getCurrentTick() - 1, false);

                            if (ppdp != null) {

                                double segmentMC = 0;
                                double mc1 = 0;
                                double acceptedAmount = 0;

                                acceptedAmount = ppdp.getAcceptedAmount();
                                mc1 = calculateMarginalCostExclCO2MarketCost(plant, getCurrentTick());
                                segmentMC = mc1 * acceptedAmount * currentSegment.getLengthInHours();
                                plantMarginalCost += segmentMC;
                            }

                        }
                        // logger.warn("Bid calculation for PowerPlant " +
                        // plant.getName());
                        // get market for the plant by zone

                        // logger.warn("CapacityMarket is  " +
                        // market.getName());

                        // for (SegmentLoad segmentLoad :
                        // eMarket.getLoadDurationCurve()) {
                        // double segmentClearingPoint = 0;
                        //
                        // if (getCurrentTick() > 0) {
                        // segmentClearingPoint =
                        // reps.segmentClearingPointRepository
                        // .findSegmentClearingPointForMarketSegmentAndTime(getCurrentTick()
                        // - 1,
                        // segmentLoad.getSegment(), eMarket, false).getPrice();
                        // } else {
                        // if (getCurrentTick() == 0) {
                        // segmentClearingPoint = 0;
                        // }
                        //
                        // }
                        // double plantLoadFactor =
                        // ((plant.getTechnology().getPeakSegmentDependentAvailability())
                        // + (((plant
                        // .getTechnology().getBaseSegmentDependentAvailability()
                        // - plant.getTechnology()
                        // .getPeakSegmentDependentAvailability()) / ((double)
                        // (reps.segmentRepository
                        // .findBaseSegmentforMarket(eMarket).getSegmentID() -
                        // 1))) * (segmentLoad
                        // .getSegment().getSegmentID() - 1)));
                        //
                        // if (segmentClearingPoint >= mc) {
                        // expectedElectricityRevenues =
                        // expectedElectricityRevenues
                        // + ((segmentClearingPoint - mc) *
                        // plant.getActualNominalCapacity()
                        // * plantLoadFactor *
                        // segmentLoad.getSegment().getLengthInHours());
                        // }
                        //
                        // }
                        // *********** New bidding strategy **********

                        double age = 0;
                        long currentLiftime = 0;
                        double ModifiedOM = 0;
                        currentLiftime = getCurrentTick() - plant.getConstructionStartTime()
                                - plant.getTechnology().getExpectedLeadtime()
                                - plant.getTechnology().getExpectedPermittime() + regulator.getTargetPeriod();

                        age = (double) currentLiftime / (((double) plant.getTechnology().getExpectedLifetime()));

                        if (age > 1.00D) {

                            ModifiedOM = plant.getActualFixedOperatingCost()
                                    * Math.pow(
                                            (1 + (plant.getTechnology().getFixedOperatingCostModifierAfterLifetime())),
                                            (currentLiftime - (((double) plant.getTechnology().getExpectedLifetime()))));
                        }
                        // logger.warn("MOM " + ModifiedOM);
                        long yearIterator = 1;
                        double cmOldRevenue = 0;
                        double cmOldCosts = 0;
                        double cmplantMarginalCost = 0;
                        double plantProfitablilty = 0;
                        double counter = 0;
                        double cmcalculatedOM = 0;
                        double finalOM = 0;

                        for (yearIterator = 1; yearIterator <= producer.getInvestorMarket().getLookback()
                                && yearIterator > 0; yearIterator++) {
                            counter += 1;

                            for (Segment currentSegment : reps.segmentRepository.findAll()) {

                                PowerPlantDispatchPlan ppdp = new PowerPlantDispatchPlan();
                                ppdp = reps.powerPlantDispatchPlanRepository
                                        .findOnePowerPlantDispatchPlanForPowerPlantForSegmentForTime(plant,
                                                currentSegment, getCurrentTick() - yearIterator, false);

                                if (ppdp != null) {

                                    double segmentMC = 0;
                                    double mc1 = 0;
                                    double acceptedAmount = 0;

                                    acceptedAmount = ppdp.getAcceptedAmount();
                                    mc1 = calculateMarginalCostExclCO2MarketCost(plant, getCurrentTick());
                                    segmentMC = mc1 * acceptedAmount * currentSegment.getLengthInHours();
                                    cmplantMarginalCost += segmentMC;
                                }

                            }

                            for (CashFlow cf : reps.cashFlowRepository.findAllCashFlowsForPowerPlantForTime(plant,
                                    (getCurrentTick() - yearIterator))) {
                                if (cf.getType() == CashFlow.ELECTRICITY_SPOT) {
                                    cmOldRevenue = cmOldRevenue + cf.getMoney();
                                }
                                if (cf.getType() == CashFlow.FIXEDOMCOST) {
                                    cmcalculatedOM = cf.getMoney();
                                }
                            }
                        }

                        if ((cmcalculatedOM / counter) > ModifiedOM) {
                            plantProfitablilty = (cmOldRevenue - cmcalculatedOM - cmplantMarginalCost) / counter;

                        } else {
                            plantProfitablilty = ((cmOldRevenue - cmplantMarginalCost) / counter) - ModifiedOM;
                        }

                        netRevenues = plantProfitablilty;

                        // ********* End New bidding strategy ********

                        // netRevenues = (expectedElectricityRevenues) -
                        // fixedOnMCost - plantMarginalCost;

                        // logger.warn("net "
                        // + netRevenues
                        // / (plant.getActualNominalCapacity() *
                        // plant.getTechnology()
                        // .getPeakSegmentDependentAvailability()));
                        if (getCurrentTick() > 0) {
                            if (netRevenues >= 0) {
                                bidPrice = 0d;
                                // } else if (mcCapacity <= fixedOnMCost) {
                            } else {
                                bidPrice = ((netRevenues * (-1)) / (plant.getActualNominalCapacity() * plant
                                        .getTechnology().getPeakSegmentDependentAvailability()));
                            }
                        } else {
                            if (getCurrentTick() == 0) {
                                bidPrice = 0;
                            }
                        }

                        double capacity = plant.getActualNominalCapacity()
                                * plant.getTechnology().getPeakSegmentDependentAvailability();

                        CapacityDispatchPlan plan = new CapacityDispatchPlan().persist();
                        plan.specifyAndPersist(plant, producer, market, getCurrentTick(), bidPrice, capacity,
                                Bid.SUBMITTED);

                        // logger.warn("CDP for powerplant " +
                        // plan.getPlant().getName()
                        // + "rev " + netRevenues);
                        // logger.warn("CDP price is " + plan.getPrice());
                        // logger.warn("CDP amount is " + plan.getAmount());
                    }
                }
            }
        }
    }
}