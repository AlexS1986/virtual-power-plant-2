import { Selector } from 'testcafe';

fixture`Main Page - Navigate to Details Page`
    .page`http://192.168.49.2:30408`;

test('U2: Details page of a battery should be able to be displayed', async t => {
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
    //check that details are available
    await t.expect(Selector("#host-div").count).eql(1,"A div that displays the current host should be available")
    await t.expect(Selector("#last-ten-energy-deposits").count).eql(1,"A div that displays the last ten energy deposits should be available")
    await t.expect(Selector("#charge-status-div").count).eql(1,"A div that displays the current charge status should be available") 

    //navigate back to main page
    await t.navigateTo(await Selector('#overview').getAttribute("href"))
    await t.wait(1000)

    // stop device simulation
    const stopButtons = await Selector(".stopButton");
    await t 
        .click(stopButtons.nth(0))
        .wait(5000)
    await t.expect(Selector(".bh-batteryWidget").count).eql(0,"No devices should be displayed 5s after stop has been clicked")
});