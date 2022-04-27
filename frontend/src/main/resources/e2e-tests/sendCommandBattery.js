import { Selector } from 'testcafe';

fixture`Details Page`
    .page`http://192.168.49.2:30408`;

test('U5: It should be possible to send commands to individual battery', async t => {
    const deviceExists = Selector(".bh-batteryWidget").exists
    
    await t.expect(deviceExists).notOk("No devices should exist before add button is clicked.")
    
    // start
    await t
        .click('#addButton')
        .wait(2000);
    await t.expect(Selector(".deviceRow").count).eql(1,"Exactly one device should exist after the add button has been clicked.")
    
    // check that there is a details link
    const detailsLink = await Selector(".deviceRow").nth(0).find('a')
    await t.expect(detailsLink.innerText).eql("details")

    //navigate to details page
    await t.navigateTo(await detailsLink.getAttribute("href"))

    const desiredChargeStatusInput = await Selector('input').withAttribute("name","desiredChargeStatusInput")
    const submitButton = await Selector('input').withAttribute("type","submit") 

    // try to decharge battery completely
    const before = await Selector("#charge-status-div").innerText
    console.log("Charge status before command: " + before)
    await t.typeText(desiredChargeStatusInput,"0", {replace: true})
    await t.click(submitButton).wait(10000)
    const after = await Selector("#charge-status-div").innerText
    console.log("Charge status after command: " + after)
    await t.expect(after).eql("0")

    //navigate back to main page
    await t.navigateTo(await Selector('#overview').getAttribute("href"))
    await t.wait(1000)

    //stop device simulation
    const stopButtons = await Selector(".stopButton");
    await t 
        .click(stopButtons.nth(0))
});