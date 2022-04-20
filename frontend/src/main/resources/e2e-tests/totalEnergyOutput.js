import { Selector } from 'testcafe';

fixture`Main Page`
    .page`http://192.168.49.2:30408`;

test('U4: Total energy output can be observed', async t => {
    const deviceExists = await Selector(".bh-batteryWidget").exists
    await t.expect(deviceExists).notOk("No devices should exist before add button is clicked.")
    
    // start devices
    await t
        .click('#addButton')
        .click('#addButton')
        .wait(2000);
    //await t.expect(Selector(".deviceRow").count).eql(2,"Exactly one device should exist after the add button has been clicked.")
    
    // check that there is a details link
    const points = await Selector("g")
        .withAttribute('class','plot')
        .child()
        .nth(0)
        .child()
        .nth(1) // class="trace scatter "
        .find("g")
        .withAttribute('class','points')
        .child()
    await t.expect(points.count).eql(10)
    // check if total energy changes
    await t.wait(2000).expect(await points.nth(8).getAttribute("transform")).notEql(await points.nth(9).getAttribute("transform")) 

    // remove devices
    await t 
        .click("#removeButton")
        .click("#removeButton")
        .wait(20000)
    await t.expect(Selector(".bh-batteryWidget").count).eql(0,"No devices should be displayed 3s after stop has been clicked")
});