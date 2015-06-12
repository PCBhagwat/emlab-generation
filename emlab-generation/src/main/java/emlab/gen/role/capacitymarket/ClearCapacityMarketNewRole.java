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
import emlab.gen.domain.agent.BigBank;
import emlab.gen.domain.agent.EnergyProducer;
import emlab.gen.domain.agent.PowerPlantManufacturer;
import emlab.gen.domain.agent.Regulator;
import emlab.gen.domain.contract.CashFlow;
import emlab.gen.domain.contract.Loan;
import emlab.gen.domain.market.Bid;
import emlab.gen.domain.market.capacity.CapacityClearingPoint;
import emlab.gen.domain.market.capacity.CapacityDispatchPlan;
import emlab.gen.domain.market.capacity.CapacityMarket;
import emlab.gen.domain.market.electricity.ElectricitySpotMarket;
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

        boolean isTheMarketCleared = false;

        double marketCap = regulator.getCapacityMarketPriceCap();
        double reserveMargin = 1 + regulator.getReserveMargin();
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

        // For UK for all unbuilt cleared power plants set long term contracts

        updatePlantsClearedForLongTermCapacityContract(market, clearingPoint, regulator);

        // Delete all unbuilt power plants that do not clear.

        ElectricitySpotMarket eMarket = reps.marketRepository.findElectricitySpotMarketForZone(market.getZone());
        // for (PowerPlant plant :
        // reps.powerPlantRepository.findPowerPlantsInMarket(eMarket)) {
        // logger.warn("1 temp " +
        // plant.isTemporaryPlantforCapacityMarketBid());
        // // logger.warn("1 LTC " +
        // // plant.isHasLongtermCapacityMarketContract());
        // }

        for (PowerPlant plant : reps.powerPlantRepository.findPowerPlantsInMarket(eMarket)) {
            if (plant.isTemporaryPlantforCapacityMarketBid() == true) {
                deleteTemporaryPowerPlants(plant, getCurrentTick());
            }
        }

    }

    /**
     * @param capacityMarketPriceCap
     * @param acceptedPrice
     * @return
     */

    public double determineLoanAnnuities(double totalLoan, double payBackTime, double interestRate) {

        double q = 1 + interestRate;
        double annuity = totalLoan * (Math.pow(q, payBackTime) * (q - 1)) / (Math.pow(q, payBackTime) - 1);

        return annuity;
    }

    @Transactional
    private void createSpreadOutDownPayments(EnergyProducer agent, PowerPlantManufacturer manufacturer,
            double totalDownPayment, PowerPlant plant) {
        int buildingTime = (int) plant.getActualLeadtime();
        reps.nonTransactionalCreateRepository.createCashFlow(agent, manufacturer, totalDownPayment / buildingTime,
                CashFlow.DOWNPAYMENT, getCurrentTick(), plant);
        Loan downpayment = reps.loanRepository.createLoan(agent, manufacturer, totalDownPayment / buildingTime,
                buildingTime - 1, getCurrentTick(), plant);
        plant.createOrUpdateDownPayment(downpayment);
    }

    @Transactional
    private void deleteTemporaryPowerPlants(PowerPlant plant, long tick) {
        plant.dismantlePowerPlant(tick);
    }

    @Transactional
    private void updateCapacityAuctionForwardYear(PowerPlant plant, long tick, Regulator regulator) {
        plant.setCapacityMarketClearingYear(getCurrentTick() + (regulator.getTargetPeriod()));
    }

    @Transactional
    private void updatePlantsClearedForLongTermCapacityContract(CapacityMarket market,
            CapacityClearingPoint clearingPoint, Regulator regulator) {
        for (CapacityDispatchPlan plan : reps.capacityMarketRepository.findAllAcceptedCapacityDispatchPlansForTime(
                market, getCurrentTick())) {
            // logger.warn("1a Loop 2 " + plan.getPlant());
            long currentLifeTime = getCurrentTick() - plan.getPlant().getConstructionStartTime()
                    - plan.getPlant().getTechnology().getExpectedLeadtime()
                    - plan.getPlant().getTechnology().getExpectedPermittime();

            if (plan.getPlant().isTemporaryPlantforCapacityMarketBid() == true) {
                PowerPlantManufacturer manufacturer = reps.genericRepository.findFirst(PowerPlantManufacturer.class);

                BigBank bigbank = reps.genericRepository.findFirst(BigBank.class);

                double investmentCostPayedByEquity = plan.getPlant().getActualInvestedCapital()
                        * (1 - plan.getPlant().getOwner().getDebtRatioOfInvestments());
                double investmentCostPayedByDebt = plan.getPlant().getActualInvestedCapital()
                        * plan.getPlant().getOwner().getDebtRatioOfInvestments();
                double downPayment = investmentCostPayedByEquity;
                createSpreadOutDownPayments(plan.getPlant().getOwner(), manufacturer, downPayment, plan.getPlant());

                double amount = determineLoanAnnuities(investmentCostPayedByDebt, plan.getPlant().getTechnology()
                        .getDepreciationTime(), plan.getPlant().getOwner().getLoanInterestRate());

                // logger.warn("Loan amount is: " + amount);
                Loan loan = reps.loanRepository.createLoan(plan.getPlant().getOwner(), bigbank, amount, plan.getPlant()
                        .getTechnology().getDepreciationTime(), getCurrentTick(), plan.getPlant());

                // Create the loan
                plan.getPlant().createOrUpdateLoan(loan);
                plan.getPlant().setLongtermcapacitycontractPrice(clearingPoint.getPrice());
                plan.getPlant().setCapacityContractPeriod(regulator.getLongTermCapacityContractLengthinYears());
                plan.getPlant().setTemporaryPlantforCapacityMarketBid(false);
                plan.getPlant().setHasLongtermCapacityMarketContract(true);
                plan.getPlant().persist();

            }
            if (currentLifeTime <= 0 && plan.getPlant().isHasLongtermCapacityMarketContract() != true
                    && plan.getPlant().isTemporaryPlantforCapacityMarketBid() != true) {
                plan.getPlant().setLongtermcapacitycontractPrice(clearingPoint.getPrice());
                plan.getPlant().setCapacityContractPeriod(regulator.getLongTermCapacityContractLengthinYears());
                plan.getPlant().setTemporaryPlantforCapacityMarketBid(false);
                plan.getPlant().setHasLongtermCapacityMarketContract(true);
                plan.getPlant().persist();
            }
        }
    }
}