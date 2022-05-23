import { Selector } from 'testcafe';

fixture`Main Page - Setting Desired Energy Output`
    .page`http://192.168.49.2:30408`
    .beforeEach( async t => {
        const deviceExists = Selector(".bh-batteryWidget").exists
        await t.expect(deviceExists).notOk("No devices should exist before add button is clicked.")
        // start
        await t
            .click('#addButton')
            .click('#addButton')
            .wait(10000);
        await t.expect(Selector(".deviceRow").count).eql(2,"Exactly one device should exist after the add button has been clicked.")
    }).afterEach(async t => {
        const stopButtons = await Selector(".stopButton");
         await t 
            .click(stopButtons.nth(1))
            .click(stopButtons.nth(0))
            .wait(5000)
    })

test('U6: It should be possible to set the desired total energy output for a VPP', async t => {
    // input fields
    const relaxationParameterField = await Selector("input").withAttribute("name","relaxationParameter")
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
    await t.expect(Math.abs(await Selector("#currentTotalEnergyOutputDiv").innerText)).lt(Math.abs(beforeTotalEnergyOutput), "The current energy output should go to zero")
});