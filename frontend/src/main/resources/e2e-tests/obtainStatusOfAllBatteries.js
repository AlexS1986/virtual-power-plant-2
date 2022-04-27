import { Selector } from 'testcafe';

fixture`Main Page`
    .page`http://192.168.49.2:30408`
    .beforeEach( async t => {
        const deviceExists = Selector(".bh-batteryWidget").exists
        await t.expect(deviceExists).notOk("No devices should exist before add button is clicked.")
        // start 10 devices
        await t
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .click('#addButton')
            .wait(10000);
        await t.expect(Selector(".deviceRow").count).eql(10,"Exactly ten devices should exist after the add button has been clicked.")
    }).afterEach(async t => {
        const stopButtons = await Selector(".stopButton");
         await t 
            .click(stopButtons.nth(9))
            .click(stopButtons.nth(8))
            .click(stopButtons.nth(7))
            .click(stopButtons.nth(6))
            .click(stopButtons.nth(5))
            .click(stopButtons.nth(4))
            .click(stopButtons.nth(3))
            .click(stopButtons.nth(2))
            .click(stopButtons.nth(1))
            .click(stopButtons.nth(0))
    })

test('U8: It should be possible to obtain the status of all batteries on the main page', async t => {
    // input fields
    const idFields = await Selector(".deviceRow")

    await t.expect(Selector(".deviceRow").count).eql(10)

    for(let i = 0; i < 10; i++) {
        const deviceRow = await Selector(".deviceRow").nth(i)

        const detailsLink = deviceRow.find('a')
        await t.expect(detailsLink.exists).ok("A details link should exist in each row")
        await t.expect(detailsLink.innerText).eql("details")

        const deviceChargeStatusElement = deviceRow.find(".bh-batteryWidget")
        await t.expect(deviceChargeStatusElement.exists).ok("A charge status illustration should exist in each row")

        const stopButton = deviceRow.find(".stopButton")
        await t.expect(stopButton.exists).ok("A stop button should exist in each row")
        await t.expect(stopButton.innerText).eql("stop")

        const tds = deviceRow.find("td")
        await t.expect(tds.count).eql(6)

    }

    /* const relaxationParameterField = await Selector("input").withAttribute("name","relaxationParameter")
    const desiredTotalEnergyOutputField = await Selector("input").withAttribute("name", "totalEnergyOutput") 
    const submitButton = await Selector("input").withAttribute("type", "submit") 

    // output fields 
    const currentTotalEnergyOutputRead = await Selector("#currentTotalEnergyOutputDiv")
    
    const beforeTotalEnergyOutput = await currentTotalEnergyOutputRead.innerText
    console.log("Energy output before desired energy output set " + beforeTotalEnergyOutput)

    // set desired output to 0.0
    await t.typeText(desiredTotalEnergyOutputField,"0.0", {replace: true})
    await t.typeText(relaxationParameterField,"20", {replace: true})
    await t.click(submitButton).wait(8000)

    // check
    console.log("Energy output after desired energy output set " + await Selector("#currentTotalEnergyOutputDiv").innerText)
    await t.expect(await Selector("#desiredTotalEnergyOutputDiv").innerText).eql("0.0", "The desired total energy output should be set to 0.0")
    await t.expect(Math.abs(await Selector("#currentTotalEnergyOutputDiv").innerText)).lt(Math.abs(beforeTotalEnergyOutput), "The current energy output should go to zero") */
});