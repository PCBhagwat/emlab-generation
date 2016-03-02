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
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.transaction.annotation.Transactional;

import agentspring.role.AbstractRole;
import agentspring.role.Role;
import agentspring.role.RoleComponent;
import emlab.gen.domain.agent.Regulator;
import emlab.gen.domain.market.Bid;
import emlab.gen.domain.market.capacity.CapacityClearingPoint;
import emlab.gen.domain.market.capacity.CapacityDispatchPlan;
import emlab.gen.domain.market.capacity.CapacityMarket;
import emlab.gen.domain.technology.PowerPlant;
import emlab.gen.repository.Reps;

/**
 * @author Prad
 * 
 */

@RoleComponent
public class ClearCapacityMarketNewRole extends AbstractRole<Regulator> implements Role<Regulator> {

    // CapacityMarketRepository capacityMarketRepository;

    @Autowired
    Reps reps;

    @Autowired
    Neo4jTemplate template;

    @Override
    @Transactional
    public void act(Regulator regulator) {

        CapacityMarket market = new CapacityMarket();
        market = reps.capacityMarketRepository.findCapacityMarketForZone(regulator.getZone());

        double phaseInPeriod = 0;

        if (getCurrentTick() <= (long) regulator.getImplementationPhaseLength()
                && regulator.getImplementationPhaseLength() > 0) {
            phaseInPeriod = regulator.getReserveMargin()
                    - ((((regulator.getReserveMargin() - regulator.getInitialSupplyMargin()) / regulator
                            .getImplementationPhaseLength()) * getCurrentTick()) + regulator.getInitialSupplyMargin());
            logger.warn("1 SET phase In " + phaseInPeriod);
        }
        logger.warn("2 TEst phase In " + phaseInPeriod);
        boolean isTheMarketCleared = false;

        double marketCap = regulator.getCapacityMarketPriceCap();
        double reserveMargin = 1 + regulator.getReserveMargin() - phaseInPeriod;
        double lowerMargin = reserveMargin - regulator.getReserveDemandLowerMargin();
        double upperMargin = reserveMargin + regulator.getReserveDemandUpperMargin();
        double demandTarget = regulator.getDemandTarget() / reserveMargin;
        double totalVolumeBid = 0;
        double totalContractedCapacity = 0;
        double clearingPrice = 0;

        if (regulator.getDemandTarget() == 0) {
            isTheMarketCleared = true;
            clearingPrice = 0;
        }

        for (CapacityDispatchPlan currentCDP : reps.capacityMarketRepository
                .findAllSortedCapacityDispatchPlansByTime(getCurrentTick())) {
            totalVolumeBid = totalVolumeBid + currentCDP.getAmount();
            // logger.warn(" Name " +
            // currentCDP.getPlant().getTechnology().getName() + " Bid " +
            // currentCDP.getPrice());
        }
        logger.warn("2 TotVol "
                + totalVolumeBid
                + " CalVol "
                + reps.powerPlantRepository.calculatePeakCapacityOfOperationalPowerPlantsInMarket(
                        reps.marketRepository.findElectricitySpotMarketForZone(regulator.getZone()), getCurrentTick())
                + " LMD " + (demandTarget * (lowerMargin)));

        if (totalVolumeBid <= (demandTarget * (lowerMargin)) && isTheMarketCleared == false) {
            for (CapacityDispatchPlan currentCDP : reps.capacityMarketRepository
                    .findAllSortedCapacityDispatchPlansByTime(getCurrentTick())) {
                currentCDP.setStatus(Bid.ACCEPTED);
                currentCDP.setAcceptedAmount(currentCDP.getAmount());
                clearingPrice = marketCap;
                totalContractedCapacity = totalVolumeBid;
            }
            isTheMarketCleared = true;
        }

        if (totalVolumeBid > (demandTarget * (lowerMargin)) && isTheMarketCleared == false) {

            for (CapacityDispatchPlan currentCDP : reps.capacityMarketRepository
                    .findAllSortedCapacityDispatchPlansByTime(getCurrentTick())) {

                if ((totalContractedCapacity + currentCDP.getAmount()) <= (demandTarget * (lowerMargin))
                        && isTheMarketCleared == false) {
                    currentCDP.setStatus(Bid.ACCEPTED);
                    currentCDP.setAcceptedAmount(currentCDP.getAmount());
                    totalContractedCapacity = totalContractedCapacity + currentCDP.getAmount();
                }

                if ((totalContractedCapacity + currentCDP.getAmount()) > (demandTarget * (lowerMargin))
                        && isTheMarketCleared == false) {
                    if ((totalContractedCapacity + currentCDP.getAmount()) < (demandTarget * ((upperMargin) - ((currentCDP
                            .getPrice() * (upperMargin - lowerMargin)) / marketCap)))) {
                        currentCDP.setStatus(Bid.ACCEPTED);
                        currentCDP.setAcceptedAmount(currentCDP.getAmount());
                        totalContractedCapacity = totalContractedCapacity + currentCDP.getAmount();
                    }
                    if ((totalContractedCapacity + currentCDP.getAmount()) > (demandTarget * ((upperMargin) - ((currentCDP
                            .getPrice() * (upperMargin - lowerMargin)) / marketCap)))) {
                        double tempAcceptedAmount = 0;
                        tempAcceptedAmount = currentCDP.getAmount()
                                - ((totalContractedCapacity + currentCDP.getAmount()) - (demandTarget * ((upperMargin) - ((currentCDP
                                        .getPrice() * (upperMargin - lowerMargin)) / marketCap))));
                        if (tempAcceptedAmount >= 0) {
                            currentCDP.setStatus(Bid.PARTLY_ACCEPTED);

                            currentCDP
                                    .setAcceptedAmount(currentCDP.getAmount()
                                            - ((totalContractedCapacity + currentCDP.getAmount()) - (demandTarget * ((upperMargin) - ((currentCDP
                                                    .getPrice() * (upperMargin - lowerMargin)) / marketCap)))));
                            clearingPrice = currentCDP.getPrice();
                            totalContractedCapacity = totalContractedCapacity + currentCDP.getAcceptedAmount();
                            isTheMarketCleared = true;
                            currentCDP.persist();
                        }
                        if (tempAcceptedAmount < 0) {
                            clearingPrice = -(marketCap / (upperMargin - lowerMargin))
                                    * ((totalContractedCapacity / demandTarget) - upperMargin);
                            isTheMarketCleared = true;
                        }

                        logger.warn("1 Pre " + (totalContractedCapacity + currentCDP.getAmount()) + " Edit "
                                + (totalContractedCapacity + currentCDP.getAcceptedAmount()));

                        logger.warn("2 true Price " + currentCDP.getPrice() + " accepted bid "
                                + currentCDP.getAcceptedAmount() + " bid qty " + currentCDP.getAmount());
                    }

                }

            }
            if (isTheMarketCleared == false) {
                clearingPrice = -(marketCap / (upperMargin - lowerMargin))
                        * ((totalContractedCapacity / demandTarget) - upperMargin);
                isTheMarketCleared = true;
            }

        }

        if (isTheMarketCleared == true) {
            for (CapacityDispatchPlan currentCDP : reps.capacityMarketRepository
                    .findAllSortedCapacityDispatchPlansByTime(getCurrentTick())) {
                if (currentCDP.getStatus() == Bid.SUBMITTED) {
                    currentCDP.setStatus(Bid.FAILED);
                    currentCDP.setAcceptedAmount(0);
                }
            }
        }

        CapacityClearingPoint clearingPoint = new CapacityClearingPoint();
        if (isTheMarketCleared == true) {
            if (clearingPrice > marketCap) {
                clearingPoint.setPrice(marketCap);
            } else {
                clearingPoint.setPrice(clearingPrice);
            }
            logger.warn("MARKET CLEARED at price" + clearingPoint.getPrice());
            clearingPoint.setVolume(totalContractedCapacity);
            clearingPoint.setTime(getCurrentTick());
            clearingPoint.setCapacityMarket(market);
            clearingPoint.persist();

            logger.warn("Clearing point Price {} and volume " + clearingPoint.getVolume(), clearingPoint.getPrice());

        }

        for (CapacityDispatchPlan plan : reps.capacityMarketRepository.findAllAcceptedCapacityDispatchPlansForTime(
                market, getCurrentTick())) {

            updatePlantsClearedforLongTermContracts(plan, clearingPoint, regulator);
            updateCapacityDeliveryYear(plan.getPlant(), regulator);

        }
    }

    @Transactional
    private void updateCapacityDeliveryYear(PowerPlant plant, Regulator regulator) {
        plant.setCapacityMarketDeliveryYear(getCurrentTick() + regulator.getTargetPeriod());
    }

    @Transactional
    private void updatePlantsClearedforLongTermContracts(CapacityDispatchPlan plan,
            CapacityClearingPoint clearingPoint, Regulator regulator) {
        long currentLifeTime = getCurrentTick() - plan.getPlant().getConstructionStartTime()
                - plan.getPlant().getTechnology().getExpectedLeadtime()
                - plan.getPlant().getTechnology().getExpectedPermittime();
        if (currentLifeTime <= 0 && plan.getPlant().isHasLongtermCapacityContracts() != true) {
            plan.getPlant().setLongtermCapacityContractPrice(clearingPoint.getPrice());
            plan.getPlant().setCapacityContractPeriod(regulator.getLongTermCapacityContractPeriod());
            plan.getPlant().setHasLongtermCapacityContracts(true);
            plan.getPlant().persist();
            // logger.warn(" Accepted Bid " + plan.getPrice() + " Name " +
            // plan.getPlant());
        }
    }
}