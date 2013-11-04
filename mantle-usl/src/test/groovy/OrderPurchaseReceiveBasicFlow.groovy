/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

/* To run these make sure moqui, and mantle are in place and run:
    "gradle cleanAll load runtime/mantle/mantle-usl:test"
   Or to quick run with saved DB copy use "gradle loadSave" once then each time "gradle reloadSave runtime/mantle/mantle-usl:test"
 */
class OrderPurchaseReceiveBasicFlow extends Specification {
    @Shared
    protected final static Logger logger = LoggerFactory.getLogger(OrderPurchaseReceiveBasicFlow.class)
    @Shared
    ExecutionContext ec
    @Shared
    String purchaseOrderId = null, orderPartSeqId
    @Shared
    Map setInfoOut, invResult, shipResult
    @Shared
    String vendorPartyId = 'MiddlemanInc', customerPartyId = 'ORG_BIZI_RETAIL'
    @Shared
    String priceUomId = 'USD', currencyUomId = 'USD'
    @Shared
    String facilityId = 'ORG_BIZI_RETAIL_WH'


    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui", null)
        // set an effective date so data check works, etc; Long value (when set from Locale of john.doe, US/Central, '2013-11-02 12:00:00.0'): 1383411600000
        ec.user.setEffectiveTime(ec.l10n.parseTimestamp("1383411600000", null))

        ec.entity.tempSetSequencedIdPrimary("mantle.ledger.transaction.AcctgTrans", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.shipment.ShipmentItemSource", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.product.asset.Asset", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.product.asset.AssetDetail", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.product.receipt.AssetReceipt", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.account.invoice.Invoice", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.account.payment.PaymentApplication", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.order.OrderItemBilling", 55400, 10)
        ec.entity.tempSetSequencedIdPrimary("moqui.entity.EntityAuditLog", 55400, 100)
        // TODO: add EntityAuditLog validation (especially status changes, etc)
    }

    def cleanupSpec() {
        ec.entity.tempResetSequencedIdPrimary("mantle.ledger.transaction.AcctgTrans")
        ec.entity.tempResetSequencedIdPrimary("mantle.shipment.ShipmentItemSource")
        ec.entity.tempResetSequencedIdPrimary("mantle.product.asset.Asset")
        ec.entity.tempResetSequencedIdPrimary("mantle.product.asset.AssetDetail")
        ec.entity.tempResetSequencedIdPrimary("mantle.product.receipt.AssetReceipt")
        ec.entity.tempResetSequencedIdPrimary("mantle.account.invoice.Invoice")
        ec.entity.tempResetSequencedIdPrimary("mantle.account.payment.PaymentApplication")
        ec.entity.tempResetSequencedIdPrimary("mantle.order.OrderItemBilling")
        ec.entity.tempResetSequencedIdPrimary("moqui.entity.EntityAuditLog")
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
    }

    def "create Purchase Order"() {
        when:
        Map priceMap = ec.service.sync().name("mantle.product.ProductServices.get#ProductPrice")
                .parameters([productId:'DEMO_1_1', priceUomId:priceUomId, quantity:1,
                    vendorPartyId:vendorPartyId, customerPartyId:customerPartyId]).call()
        Map priceMap2 = ec.service.sync().name("mantle.product.ProductServices.get#ProductPrice")
                .parameters([productId:'DEMO_1_1', priceUomId:priceUomId, quantity:100,
                    vendorPartyId:vendorPartyId, customerPartyId:customerPartyId]).call()

        // no store, etc for purchase orders so explicitly create order and set parties
        Map orderOut = ec.service.sync().name("mantle.order.OrderServices.create#Order")
                .parameters([customerPartyId:customerPartyId, vendorPartyId:vendorPartyId, currencyUomId:currencyUomId])
                .call()

        purchaseOrderId = orderOut.orderId
        orderPartSeqId = orderOut.orderPartSeqId

        Map addOut1 = ec.service.sync().name("mantle.order.OrderServices.add#OrderProductQuantity")
                .parameters([orderId:purchaseOrderId, orderPartSeqId:orderPartSeqId, productId:'DEMO_1_1', quantity:150,
                    itemTypeEnumId:'ItemProduct'])
                .call()
        Map addOut2 = ec.service.sync().name("mantle.order.OrderServices.add#OrderProductQuantity")
                .parameters([orderId:purchaseOrderId, orderPartSeqId:orderPartSeqId, productId:'DEMO_3_1', quantity:100,
                    itemTypeEnumId:'ItemProduct'])
                .call()

        setInfoOut = ec.service.sync().name("mantle.order.OrderServices.set#OrderBillingShippingInfo")
                .parameters([orderId:purchaseOrderId, orderPartSeqId:orderPartSeqId,
                    paymentMethodTypeEnumId:'PmtCompanyCheck', shippingPostalContactMechId:'ORG_BIZI_RTL_SA',
                    shippingTelecomContactMechId:'ORG_BIZI_RTL_PT', shipmentMethodEnumId:'ShMthNoShipping']).call()

        // one person will place the PO
        ec.service.sync().name("mantle.order.OrderServices.place#Order").parameters([orderId:purchaseOrderId]).call()
        // typically another person will approve the PO
        ec.service.sync().name("mantle.order.OrderServices.approve#Order").parameters([orderId:purchaseOrderId]).call()
        // then the PO is sent to the vendor/supplier

        // NOTE: this has sequenced IDs so is sensitive to run order!
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <mantle.order.OrderHeader orderId="${purchaseOrderId}" entryDate="1383411600000" placedDate="1383411600000"
                statusId="OrderApproved" currencyUomId="USD" grandTotal="1650.00"/>

            <mantle.account.payment.Payment paymentId="${setInfoOut.paymentId}"
                paymentMethodTypeEnumId="PmtCompanyCheck" orderId="${purchaseOrderId}" orderPartSeqId="01"
                statusId="PmntPromised" amount="1650.00" amountUomId="USD"/>

            <mantle.order.OrderPart orderId="${purchaseOrderId}" orderPartSeqId="01" vendorPartyId="MiddlemanInc"
                customerPartyId="ORG_BIZI_RETAIL" shipmentMethodEnumId="ShMthNoShipping" postalContactMechId="ORG_BIZI_RTL_SA"
                telecomContactMechId="ORG_BIZI_RTL_PT" partTotal=""/>
            <mantle.order.OrderItem orderId="${purchaseOrderId}" orderItemSeqId="01" orderPartSeqId="01" itemTypeEnumId="ItemProduct"
                productId="DEMO_1_1" itemDescription="Demo Product One-One" quantity="150" unitAmount="8.00"
                isModifiedPrice="N"/>
            <mantle.order.OrderItem orderId="${purchaseOrderId}" orderItemSeqId="02" orderPartSeqId="01" itemTypeEnumId="ItemProduct"
                productId="DEMO_3_1" itemDescription="Demo Product Three-One" quantity="100" unitAmount="4.50"
                isModifiedPrice="N"/>
        </entity-facade-xml>""").check()
        logger.info("create Purchase Order data check results: " + dataCheckErrors)

        then:
        priceMap.price == 9.00
        priceMap2.price == 8.00
        priceMap.priceUomId == 'USD'
        vendorPartyId == 'MiddlemanInc'
        customerPartyId == 'ORG_BIZI_RETAIL'

        dataCheckErrors.size() == 0
    }

    def "create Purchase Order Shipment and Schedule"() {
        when:
        shipResult = ec.service.sync().name("mantle.shipment.ShipmentServices.create#OrderPartShipment")
                .parameters([orderId:purchaseOrderId, orderPartSeqId:orderPartSeqId, destinationFacilityId:facilityId]).call()

        // TODO: add PO Shipment Schedule, update status to ShipScheduled

        // NOTE: this has sequenced IDs so is sensitive to run order!
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <!-- Shipment created -->
            <mantle.shipment.Shipment shipmentId="${shipResult.shipmentId}" shipmentTypeEnumId="ShpTpPurchase"
                statusId="ShipInput" fromPartyId="MiddlemanInc" toPartyId="ORG_BIZI_RETAIL"/>
            <mantle.shipment.ShipmentPackage shipmentId="${shipResult.shipmentId}" shipmentPackageSeqId="01"/>

            <mantle.shipment.ShipmentItem shipmentId="${shipResult.shipmentId}" productId="DEMO_1_1" quantity="150"/>
            <mantle.shipment.ShipmentItemSource shipmentItemSourceId="55400" shipmentId="${shipResult.shipmentId}"
                productId="DEMO_1_1" orderId="${purchaseOrderId}" orderItemSeqId="01" statusId="SisPending" quantity="150"
                invoiceId="" invoiceItemSeqId=""/>

            <mantle.shipment.ShipmentItem shipmentId="${shipResult.shipmentId}" productId="DEMO_3_1" quantity="100"/>
            <mantle.shipment.ShipmentItemSource shipmentItemSourceId="55401" shipmentId="${shipResult.shipmentId}"
                productId="DEMO_3_1" orderId="${purchaseOrderId}" orderItemSeqId="02" statusId="SisPending" quantity="100"
                invoiceId="" invoiceItemSeqId=""/>

            <!-- no SPC when not outgoing packed, can be added by something else though:
            <mantle.shipment.ShipmentPackageContent shipmentId="${shipResult.shipmentId}" shipmentPackageSeqId="01"
                productId="DEMO_1_1" quantity="150"/>
            <mantle.shipment.ShipmentPackageContent shipmentId="${shipResult.shipmentId}" shipmentPackageSeqId="01"
                productId="DEMO_3_1" quantity="100"/>
            -->

            <mantle.shipment.ShipmentRouteSegment shipmentId="${shipResult.shipmentId}" shipmentRouteSegmentSeqId="01"
                destPostalContactMechId="ORG_BIZI_RTL_SA" destTelecomContactMechId="ORG_BIZI_RTL_PT"/>
            <mantle.shipment.ShipmentPackageRouteSeg shipmentId="${shipResult.shipmentId}" shipmentPackageSeqId="01"
                shipmentRouteSegmentSeqId="01"/>
        </entity-facade-xml>""").check()
        logger.info("receive Purchase Order data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    def "process Purchase Invoice"() {
        when:
        // NOTE: in real-world scenarios the invoice received may not match what is expected, may be for multiple or
        //     partial purchase orders, etc; for this we'll simply create an invoice automatically from the Order
        // to somewhat simulate real-world, create in InvoiceIncoming then change to InvoiceReceived to allow for manual
        //     changes between

        // set Shipment Shipped
        ec.service.sync().name("mantle.shipment.ShipmentServices.ship#Shipment")
                .parameters([shipmentId:shipResult.shipmentId]).call()

        invResult = ec.service.sync().name("mantle.account.InvoiceServices.create#EntireOrderPartInvoice")
                .parameters([orderId:purchaseOrderId, orderPartSeqId:orderPartSeqId]).call()

        ec.service.sync().name("update#mantle.account.invoice.Invoice")
                .parameters([invoiceId:invResult.invoiceId, statusId:'InvoiceReceived']).call()
        
        // NOTE: this has sequenced IDs so is sensitive to run order!
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <!-- Shipment to Shipped status -->
            <mantle.shipment.Shipment shipmentId="${shipResult.shipmentId}" shipmentTypeEnumId="ShpTpPurchase"
                statusId="ShipShipped" fromPartyId="MiddlemanInc" toPartyId="ORG_BIZI_RETAIL"/>

            <!-- Invoice created and received, not yet approved/etc -->
            <mantle.account.invoice.Invoice invoiceId="${invResult.invoiceId}" invoiceTypeEnumId="InvoiceSales" fromPartyId="MiddlemanInc"
                toPartyId="ORG_BIZI_RETAIL" statusId="InvoiceReceived" invoiceDate="1383411600000"
                description="Invoice for Order ${purchaseOrderId} part 01" currencyUomId="USD"/>

            <mantle.account.invoice.InvoiceItem invoiceId="${invResult.invoiceId}" invoiceItemSeqId="01" itemTypeEnumId="ItemProduct"
                productId="DEMO_1_1" quantity="150" amount="8.00" description="Demo Product One-One" itemDate="1383411600000"/>
            <mantle.order.OrderItemBilling orderItemBillingId="55400" orderId="${purchaseOrderId}" orderItemSeqId="01"
                invoiceId="${invResult.invoiceId}" invoiceItemSeqId="01" quantity="150" amount="8.00"
                shipmentId="${shipResult.shipmentId}"/>

            <mantle.account.invoice.InvoiceItem invoiceId="${invResult.invoiceId}" invoiceItemSeqId="02" itemTypeEnumId="ItemProduct"
                productId="DEMO_3_1" quantity="100" amount="4.50" description="Demo Product Three-One" itemDate="1383411600000"/>
            <mantle.order.OrderItemBilling orderItemBillingId="55401" orderId="${purchaseOrderId}" orderItemSeqId="02"
                invoiceId="${invResult.invoiceId}" invoiceItemSeqId="02" quantity="100" amount="4.50"
                shipmentId="${shipResult.shipmentId}"/>

            <mantle.shipment.ShipmentItemSource shipmentItemSourceId="55400" shipmentId="${shipResult.shipmentId}"
                productId="DEMO_1_1" orderId="${purchaseOrderId}" orderItemSeqId="01" statusId="SisPending" quantity="150"
                invoiceId="${invResult.invoiceId}" invoiceItemSeqId="01"/>
            <mantle.shipment.ShipmentItemSource shipmentItemSourceId="55401" shipmentId="${shipResult.shipmentId}"
                productId="DEMO_3_1" orderId="${purchaseOrderId}" orderItemSeqId="02" statusId="SisPending" quantity="100"
                invoiceId="${invResult.invoiceId}" invoiceItemSeqId="02"/>
        </entity-facade-xml>""").check()
        logger.info("validate Shipment Invoice data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    def "receive Purchase Order Shipment"() {
        when:
        // receive the Shipment, create AssetReceipt records, status to ShipDelivered
        ec.service.sync().name("mantle.shipment.ShipmentServices.receive#EntireShipment")
                .parameters([shipmentId:shipResult.shipmentId]).call()

        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <mantle.shipment.Shipment shipmentId="${shipResult.shipmentId}" shipmentTypeEnumId="ShpTpPurchase"
                statusId="ShipDelivered" fromPartyId="MiddlemanInc" toPartyId="ORG_BIZI_RETAIL"/>
        </entity-facade-xml>""").check()
        logger.info("receive Purchase Order data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    def "complete Purchase Order"() {
        when:
        // after Shipment Packed mark Order as Completed
        ec.service.sync().name("mantle.order.OrderServices.complete#OrderPart")
                .parameters([orderId:purchaseOrderId, orderPartSeqId:orderPartSeqId]).call()

        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <!-- OrderHeader status to Completed -->
            <mantle.order.OrderHeader orderId="${purchaseOrderId}" statusId="OrderCompleted"/>
        </entity-facade-xml>""").check()
        logger.info("validate Purchase Order Complete data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    def "validate Assets Received"() {
        when:
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <mantle.product.asset.Asset assetId="55400" assetTypeEnumId="AstTpInventory" statusId="AstAvailable"
                ownerPartyId="ORG_BIZI_RETAIL" productId="DEMO_1_1" hasQuantity="Y" quantityOnHandTotal="150"
                availableToPromiseTotal="150" assetName="Demo Product One-One" receivedDate="1383411600000"
                acquiredDate="1383411600000" facilityId="ORG_BIZI_RETAIL_WH" acquireOrderId="${purchaseOrderId}"
                acquireOrderItemSeqId="01" acquireCost="8" acquireCostUomId="USD"/>
            <mantle.product.receipt.AssetReceipt assetReceiptId="55400" assetId="55400" productId="DEMO_1_1"
                orderId="${purchaseOrderId}" orderItemSeqId="01" shipmentId="${shipResult.shipmentId}"
                receivedByUserId="EX_JOHN_DOE" receivedDate="1383411600000" quantityAccepted="150"/>
            <mantle.product.asset.AssetDetail assetDetailId="55400" assetId="55400" effectiveDate="1383411600000"
                quantityOnHandDiff="150" availableToPromiseDiff="150" unitCost="8" shipmentId="${shipResult.shipmentId}"
                productId="DEMO_1_1" assetReceiptId="55400"/>

            <mantle.product.asset.Asset assetId="55401" assetTypeEnumId="AstTpInventory" statusId="AstAvailable"
                ownerPartyId="ORG_BIZI_RETAIL" productId="DEMO_3_1" hasQuantity="Y" quantityOnHandTotal="100"
                availableToPromiseTotal="100" assetName="Demo Product Three-One" receivedDate="1383411600000"
                acquiredDate="1383411600000" facilityId="ORG_BIZI_RETAIL_WH" acquireOrderId="${purchaseOrderId}"
                acquireOrderItemSeqId="02" acquireCost="4.5" acquireCostUomId="USD"/>
            <mantle.product.receipt.AssetReceipt assetReceiptId="55401" assetId="55401" productId="DEMO_3_1"
                orderId="${purchaseOrderId}" orderItemSeqId="02" shipmentId="${shipResult.shipmentId}"
                receivedByUserId="EX_JOHN_DOE" receivedDate="1383411600000" quantityAccepted="100"/>
            <mantle.product.asset.AssetDetail assetDetailId="55401" assetId="55401" effectiveDate="1383411600000"
                quantityOnHandDiff="100" availableToPromiseDiff="100" unitCost="4.5" shipmentId="${shipResult.shipmentId}"
                productId="DEMO_3_1" assetReceiptId="55401"/>

            <!-- verify assetReceiptId set on OrderItemBilling, and that all else is the same -->
            <mantle.order.OrderItemBilling orderItemBillingId="55400" orderId="${purchaseOrderId}" orderItemSeqId="01"
                invoiceId="${invResult.invoiceId}" invoiceItemSeqId="01" quantity="150" amount="8.00"
                shipmentId="${shipResult.shipmentId}" assetReceiptId="55400"/>
            <mantle.order.OrderItemBilling orderItemBillingId="55401" orderId="${purchaseOrderId}" orderItemSeqId="02"
                invoiceId="${invResult.invoiceId}" invoiceItemSeqId="02" quantity="100" amount="4.50"
                shipmentId="${shipResult.shipmentId}" assetReceiptId="55401"/>
        </entity-facade-xml>""").check()
        logger.info("validate Assets Received data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }


    /*
    def "validate Assets Receipt Accounting Transactions"() {
        when:

        // TODO: implement asset receipt and issue GL postings

        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>


        </entity-facade-xml>""").check()
        logger.info("validate Assets Received data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }
    */

    def "approve Purchase Invoice"() {
        when:
        // approve Invoice from Vendor (will trigger GL posting)
        ec.service.sync().name("update#mantle.account.invoice.Invoice")
                .parameters([invoiceId:invResult.invoiceId, statusId:'InvoiceApproved']).call()

        // NOTE: this has sequenced IDs so is sensitive to run order!
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <mantle.account.invoice.Invoice invoiceId="${invResult.invoiceId}" statusId="InvoiceApproved"/>
        </entity-facade-xml>""").check()
        logger.info("validate Shipment Invoice data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    def "validate Purchase Invoice Accounting Transaction"() {
        when:
        // NOTE: this has sequenced IDs so is sensitive to run order!
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <!-- AcctgTrans created for Approved Invoice -->
            <mantle.ledger.transaction.AcctgTrans acctgTransId="55400" acctgTransTypeEnumId="AttPurchaseInvoice"
                organizationPartyId="ORG_BIZI_RETAIL" transactionDate="1383411600000" isPosted="Y"
                postedDate="1383411600000" glFiscalTypeEnumId="GLFT_ACTUAL" amountUomId="USD"
                otherPartyId="MiddlemanInc" invoiceId="${invResult.invoiceId}"/>
            <mantle.ledger.transaction.AcctgTransEntry acctgTransId="55400" acctgTransEntrySeqId="01" debitCreditFlag="D"
                amount="1,200" glAccountId="501000" reconcileStatusId="AES_NOT_RECONCILED" isSummary="N"
                productId="DEMO_1_1" invoiceItemSeqId="01"/>
            <mantle.ledger.transaction.AcctgTransEntry acctgTransId="55400" acctgTransEntrySeqId="02" debitCreditFlag="D"
                amount="450" glAccountId="501000" reconcileStatusId="AES_NOT_RECONCILED" isSummary="N"
                productId="DEMO_3_1" invoiceItemSeqId="02"/>
            <mantle.ledger.transaction.AcctgTransEntry acctgTransId="55400" acctgTransEntrySeqId="03" debitCreditFlag="C"
                amount="1,650" glAccountTypeEnumId="ACCOUNTS_PAYABLE" glAccountId="210000"
                reconcileStatusId="AES_NOT_RECONCILED" isSummary="N"/>
        </entity-facade-xml>""").check()
        logger.info("validate Shipment Invoice Accounting Transaction data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    def "send Purchase Invoice Payment"() {
        when:
        // record Payment for Invoice (will trigger GL posting)
        Map sendPmtResult = ec.service.sync().name("mantle.account.PaymentServices.send#PromisedPayment")
                .parameters([invoiceId:invResult.invoiceId, paymentId:setInfoOut.paymentId]).call()

        // NOTE: this has sequenced IDs so is sensitive to run order!
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <mantle.account.payment.PaymentApplication paymentApplicationId="${sendPmtResult.paymentApplicationId}"
                paymentId="${setInfoOut.paymentId}" invoiceId="${invResult.invoiceId}" amountApplied="1650.00"
                appliedDate="1383411600000"/>
            <!-- Payment to Delivered status, set effectiveDate -->
            <mantle.account.payment.Payment paymentId="${setInfoOut.paymentId}" statusId="PmntDelivered"
                effectiveDate="1383411600000"/>
            <!-- Invoice to Payment Sent status -->
            <mantle.account.invoice.Invoice invoiceId="${invResult.invoiceId}" invoiceTypeEnumId="InvoiceSales"
                fromPartyId="MiddlemanInc" toPartyId="ORG_BIZI_RETAIL" statusId="InvoicePmtSent" invoiceDate="1383411600000"
                description="Invoice for Order ${purchaseOrderId} part 01" currencyUomId="USD"/>
        </entity-facade-xml>""").check()
        logger.info("validate Shipment Invoice data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    def "validate Purchase Payment Accounting Transaction"() {
        when:
        // NOTE: this has sequenced IDs so is sensitive to run order!
        List<String> dataCheckErrors = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <!-- AcctgTrans created for Delivered Payment -->
            <mantle.ledger.transaction.AcctgTrans acctgTransId="55401" acctgTransTypeEnumId="AttOutgoingPayment"
                organizationPartyId="ORG_BIZI_RETAIL" transactionDate="1383411600000" isPosted="Y"
                postedDate="1383411600000" glFiscalTypeEnumId="GLFT_ACTUAL" amountUomId="USD"
                otherPartyId="MiddlemanInc" paymentId="${setInfoOut.paymentId}"/>
            <mantle.ledger.transaction.AcctgTransEntry acctgTransId="55401" acctgTransEntrySeqId="01" debitCreditFlag="D"
                amount="1,650" glAccountId="216000" reconcileStatusId="AES_NOT_RECONCILED" isSummary="N"/>
            <mantle.ledger.transaction.AcctgTransEntry acctgTransId="55401" acctgTransEntrySeqId="02" debitCreditFlag="C"
                amount="1,650" glAccountId="111100" reconcileStatusId="AES_NOT_RECONCILED" isSummary="N"/>
        </entity-facade-xml>""").check()
        logger.info("validate Shipment Invoice Accounting Transaction data check results: " + dataCheckErrors)

        then:
        dataCheckErrors.size() == 0
    }

    // TODO: ===========================
    // TODO: alternate flow where invoice only created when items received using create#PurchaseShipmentInvoices
}
