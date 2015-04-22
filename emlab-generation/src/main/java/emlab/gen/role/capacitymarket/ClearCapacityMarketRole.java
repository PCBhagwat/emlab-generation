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
 *
 * 
 */

@RoleComponent
public class ClearCapacityMarketRole extends AbstractRole<Regulator> implements Role<Regulator> {

    // CapacityMarketRepository capacityMarketRepository;
    // Provide cleared unbuilt power plants long term contract

    @Autowired
    Reps reps;

    @Autowired
    Neo4jTemplate template;

    @Override
    @Transactional
    public void act(Regulator regulator) {

        CapacityMarket market = new CapacityMarket();
        market = reps.capacityMarketRepository.findCapacityMarketForZone(regulator.getZone());

        Iterable<CapacityDispatchPlan> sortedListofCDP = null;
        sortedListofCDP = reps.capacityMarketRepository.findAllSortedCapacityDispatchPlansByTime(getCurrentTick());
        double demand = 0d;
        double sumofSupplyBidsAccepted = 0d;
        double acceptedPrice = 0d;
        boolean isTheMarketCleared = false;

        // This epsilon is to account for rounding errors for java (only
        // relevant for exact clearing)
        double clearingEpsilon = 0.001d;

        if (regulator.getDemandTarget() == 0) {
            isTheMarketCleared = true;
            acceptedPrice = 0;
        }

        for (CapacityDispatchPlan currentCDP : sortedListofCDP) {
            // logger.warn("Plant type" +
            // currentCDP.getPlant().isTemporaryPlantforCapacityMarketBid()
            // + " dispatch Price " + currentCDP.getPrice());
            if (currentCDP.getPrice() <= regulator.getCapacityMarketPriceCap()) {

                demand = regulator.getDemandTarget()
                        * (1 - regulator.getReserveDemandLowerMargin())
                        + ((regulator.getCapacityMarketPriceCap() - currentCDP.getPrice())
                                * (regulator.getReserveDemandUpperMargin() + regulator.getReserveDemandLowerMargin()) * regulator
                                    .getDemandTarget()) / regulator.getCapacityMarketPriceCap();

                // logger.warn("Price of this cdp is " + currentCDP.getPrice());
                // logger.warn("Demand at this cdp is " + demand);

                if (isTheMarketCleared == false) {
                    if (demand - (sumofSupplyBidsAccepted + currentCDP.getAmount()) >= -clearingEpsilon) {
                        acceptedPrice = currentCDP.getPrice();
                        currentCDP.setStatus(Bid.ACCEPTED);
                        currentCDP.setAcceptedAmount(currentCDP.getAmount());
                        sumofSupplyBidsAccepted = sumofSupplyBidsAccepted + currentCDP.getAmount();
                        // logger.warn("Price of this cdp is " +
                        // currentCDP.getPrice());
                        // logger.warn("accepted price" + acceptedPrice);
                    }

                    else if (demand - (sumofSupplyBidsAccepted + currentCDP.getAmount()) < clearingEpsilon) {

                        currentCDP.setStatus(Bid.PARTLY_ACCEPTED);
                        currentCDP.setAcceptedAmount((sumofSupplyBidsAccepted - demand));
                        acceptedPrice = currentCDP.getPrice();
                        sumofSupplyBidsAccepted = sumofSupplyBidsAccepted + currentCDP.getAcceptedAmount();
                        isTheMarketCleared = true;

                        // logger.warn("accepted price" + acceptedPrice);

                    }

                    // else if (demand - sumofSupplyBidsAccepted <
                    // clearingEpsilon) {
                    // isTheMarketCleared = true;
                    // }
                } else {
                    currentCDP.setStatus(Bid.FAILED);
                    currentCDP.setAcceptedAmount(0);
                }

                // logger.warn("Cumulatively Accepted Supply " +
                // sumofSupplyBidsAccepted);
                currentCDP.persist();

            }

            // logger.warn("Current CDP Price " + currentCDP.getPrice());
            // logger.warn("Cumulatively accepted volume " +
            // sumofSupplyBidsAccepted);
        }

        // logger.warn("Demand for the capacity market at tick {} is " + demand,
        // getCurrentTick());

        CapacityClearingPoint clearingPoint = new CapacityClearingPoint();
        if (isTheMarketCleared == true) {
            // sumofSupplyBidsAccepted = demand;
            logger.warn("MARKET CLEARED at price" + acceptedPrice);
            clearingPoint.setPrice(acceptedPrice);
            clearingPoint.setVolume(sumofSupplyBidsAccepted);
            clearingPoint.setTime(getCurrentTick());
            clearingPoint.setCapacityMarket(market);
            clearingPoint.persist();

            logger.warn("Clearing point Price {} and volume " + clearingPoint.getVolume(), clearingPoint.getPrice());

        } else {
            acceptedPrice = regulator.getCapacityMarketPriceCap()
                    * (1 + ((regulator.getDemandTarget() * (1 - regulator.getReserveDemandLowerMargin()) - sumofSupplyBidsAccepted) / ((regulator
                            .getReserveDemandUpperMargin() + regulator.getReserveDemandLowerMargin()) * regulator
                            .getDemandTarget())));
            clearingPoint.setPrice(max(regulator.getCapacityMarketPriceCap(), acceptedPrice));
            clearingPoint.setVolume(sumofSupplyBidsAccepted);
            clearingPoint.setTime(getCurrentTick());
            clearingPoint.setCapacityMarket(market);
            clearingPoint.persist();
            logger.warn("MARKET UNCLEARED at price" + clearingPoint.getPrice());
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
                plant.dismantlePowerPlant(getCurrentTick());
            }
        }

        // clearingPoint.persist();
        // logger.warn("is the market cleared? " + isTheMarketCleared);
        // logger.warn("Clearing point Price" + clearingPoint.getPrice());
        // logger.warn("Clearing Point Volume" + clearingPoint.getVolume());

        // VERIFICATION
        double q2 = clearingPoint.getVolume();
        double q1 = regulator.getDemandTarget()
                * (1 - regulator.getReserveDemandLowerMargin())
                + ((regulator.getCapacityMarketPriceCap() - clearingPoint.getPrice())
                        * (regulator.getReserveDemandUpperMargin() + regulator.getReserveDemandLowerMargin()) * regulator
                            .getDemandTarget()) / regulator.getCapacityMarketPriceCap();
        if (q1 == q2) {
            logger.warn("matches");
        } else {
            logger.warn("does not match");
        }

    }

    /**
     * @param capacityMarketPriceCap
     * @param acceptedPrice
     * @return
     */
    private double max(double capacityMarketPriceCap, double acceptedPrice) {
        if (acceptedPrice >= capacityMarketPriceCap)
            return capacityMarketPriceCap;
        else
            return acceptedPrice;
    }

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
                Loan loan = reps.loanRepository.createLoan(plan.getBidder(), bigbank, amount, plan.getPlant()
                        .getTechnology().getDepreciationTime(), getCurrentTick(), plan.getPlant());

                // Create the loan
                plan.getPlant().createOrUpdateLoan(loan);
                plan.getPlant().setLongtermcapacitycontractPrice(clearingPoint.getPrice());
                plan.getPlant().setCapacityContractPeriod(regulator.getLongTermCapacityContractLengthinYears());
                plan.getPlant().setTemporaryPlantforCapacityMarketBid(false);
                plan.getPlant().setHasLongtermCapacityMarketContract(true);
                plan.getPlant().persist();

            }
            if (currentLifeTime < 0 && plan.getPlant().isHasLongtermCapacityMarketContract() != true) {
                plan.getPlant().setLongtermcapacitycontractPrice(clearingPoint.getPrice());
                plan.getPlant().setCapacityContractPeriod(regulator.getLongTermCapacityContractLengthinYears());
                plan.getPlant().setTemporaryPlantforCapacityMarketBid(false);
                plan.getPlant().setHasLongtermCapacityMarketContract(true);
                plan.getPlant().persist();
            }
        }
    }

}